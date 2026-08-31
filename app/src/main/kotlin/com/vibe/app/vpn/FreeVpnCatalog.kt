package com.vibe.app.vpn

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.nio.charset.StandardCharsets
import kotlin.math.min

/**
 * Strict free-relay discovery.
 *
 * A source saying that an endpoint belongs to a country is never enough. Every candidate must:
 * 1) parse into a supported, public endpoint,
 * 2) pass a protocol-level reachability check,
 * 3) pass independent endpoint geolocation checks for the requested country,
 * 4) then (outside this class) prove the real tunnel exit country after sing-box starts.
 *
 * Community V2Ray files previously labelled by country are intentionally excluded because real
 * device testing showed that those files can contain endpoints from unrelated countries and even
 * loopback addresses.
 */
class FreeVpnCatalog {
    suspend fun discover(country: VpnCountry): List<FreeVpnCandidate> = withContext(Dispatchers.IO) {
        val sourceCandidates = coroutineScope {
            sourcesFor(country).map { source ->
                async(Dispatchers.IO) {
                    runCatching { fetch(source.url) }
                        .getOrNull()
                        .orEmpty()
                        .lineSequence()
                        .map(String::trim)
                        .filter { line -> line.isNotEmpty() && !line.startsWith('#') }
                        .map(source::normalize)
                        .mapNotNull { line -> ProxyShareParser.parse(line, source.id) }
                        .filter { candidate -> candidate.isSafePublicEndpoint() }
                        .map { candidate ->
                            candidate.copy(countryEvidence = CountryEvidence.SOURCE_COUNTRY_CLAIM)
                        }
                        .distinctBy { candidate -> candidate.fingerprint }
                        .take(MAX_PER_SOURCE)
                        .toList()
                }
            }.awaitAll()
        }

        val raw = roundRobin(sourceCandidates)
            .distinctBy { candidate -> candidate.fingerprint }
            .take(MAX_PREFLIGHT_CANDIDATES)

        // Dead public proxies are far more common than live ones. Check the actual proxy protocol
        // first so scarce geolocation requests are only spent on endpoints that can carry traffic.
        val preflightBudget = Semaphore(MAX_PREFLIGHT_CONCURRENCY)
        val live = coroutineScope {
            raw.map { candidate ->
                async(Dispatchers.IO) {
                    preflightBudget.withPermit {
                        candidate.copy(preflightLatencyMs = protocolPreflight(candidate))
                    }
                }
            }.awaitAll()
                .filter { it.preflightLatencyMs != null }
                .sortedBy { it.preflightLatencyMs ?: Long.MAX_VALUE }
                .take(MAX_GEO_CANDIDATES)
        }

        // Every endpoint is geolocated even when the upstream API already claimed a country.
        // Conflicting geo providers cause a hard rejection rather than a guessed connection.
        val geoBudget = Semaphore(MAX_GEO_CONCURRENCY)
        val geoVerified = coroutineScope {
            live.map { candidate ->
                async(Dispatchers.IO) {
                    geoBudget.withPermit {
                        candidate.takeIf { endpointCountryMatchesStrict(it, country) }
                            ?.copy(countryEvidence = CountryEvidence.ENDPOINT_GEO_VERIFIED)
                    }
                }
            }.awaitAll().filterNotNull()
        }

        geoVerified
            .sortedWith(
                compareBy<FreeVpnCandidate> { protocolPriority(it.protocol) }
                    .thenBy { it.preflightLatencyMs ?: Long.MAX_VALUE }
            )
            .take(MAX_CONNECT_ATTEMPTS)
    }

    private fun sourcesFor(country: VpnCountry): List<CatalogSource> {
        val upperCode = country.code.uppercase()
        val lowerCode = country.code.lowercase()
        val hproxyBase = "https://hproxy.com/api/proxy-list?format=txt&country=$upperCode&limit=100"
        val proxyScrapeBase =
            "https://api.proxyscrape.com/v4/free-proxy-list/get?request=display_proxies" +
                "&proxy_format=protocolipport&format=text&country=$lowerCode&timeout=10000"

        return listOf(
            CatalogSource(
                id = "proxyscrape-http-live",
                url = "$proxyScrapeBase&protocol=http",
                linePrefix = "http://",
            ),
            CatalogSource(
                id = "proxyscrape-socks5-live",
                url = "$proxyScrapeBase&protocol=socks5",
                linePrefix = "socks5://",
            ),
            CatalogSource(
                id = "proxyscrape-socks4-live",
                url = "$proxyScrapeBase&protocol=socks4",
                linePrefix = "socks4://",
            ),
            CatalogSource(
                id = "proxyscrape-country-mirror",
                url = "https://raw.githubusercontent.com/ProxyScrape/free-proxy-list/main/proxies/countries/$lowerCode/data.txt",
            ),
            CatalogSource(
                id = "hproxy-http-live",
                url = "$hproxyBase&protocol=http",
                linePrefix = "http://",
            ),
            CatalogSource(
                id = "hproxy-https-live",
                url = "$hproxyBase&protocol=https",
                linePrefix = "https://",
            ),
            CatalogSource(
                id = "hproxy-socks5-live",
                url = "$hproxyBase&protocol=socks5",
                linePrefix = "socks5://",
            ),
            CatalogSource(
                id = "hproxy-socks4-live",
                url = "$hproxyBase&protocol=socks4",
                linePrefix = "socks4://",
            ),
            CatalogSource(
                id = "proxifly-country",
                url = "https://raw.githubusercontent.com/proxifly/free-proxy-list/main/proxies/countries/$upperCode/data.txt",
            ),
        )
    }

    private fun fetch(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = FETCH_TIMEOUT_MS
            readTimeout = FETCH_TIMEOUT_MS
            requestMethod = "GET"
            useCaches = false
            instanceFollowRedirects = true
            setRequestProperty("Accept", "text/plain,*/*;q=0.2")
            setRequestProperty("User-Agent", "ArabVPN/1.3 Android")
        }
        return try {
            val code = connection.responseCode
            if (code !in 200..299) return ""
            val declared = connection.contentLengthLong
            if (declared > MAX_SOURCE_BYTES) return ""
            connection.inputStream.bufferedReader().use { reader ->
                val out = StringBuilder(min(declared.coerceAtLeast(0L), 64_000L).toInt())
                val buffer = CharArray(8 * 1024)
                var total = 0L
                while (true) {
                    val read = reader.read(buffer)
                    if (read <= 0) break
                    total += read
                    if (total > MAX_SOURCE_BYTES) return ""
                    out.append(buffer, 0, read)
                }
                out.toString()
            }
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun endpointCountryMatchesStrict(
        candidate: FreeVpnCandidate,
        country: VpnCountry,
    ): Boolean = coroutineScope {
        val address = resolvePublicAddress(candidate.server) ?: return@coroutineScope false
        val ip = address.hostAddress ?: return@coroutineScope false

        val ipWho = async(Dispatchers.IO) { lookupIpWhoCountry(ip) }
        val countryIs = async(Dispatchers.IO) { lookupCountryIsCountry(ip) }

        CountryVerificationPolicy.accept(
            expected = country.code,
            providerA = ipWho.await(),
            providerB = countryIs.await(),
        )
    }

    private fun lookupIpWhoCountry(ip: String): String? {
        val body = fetchGeoJson("https://ipwho.is/$ip") ?: return null
        return IPWHO_COUNTRY_CODE_REGEX.find(body)?.groupValues?.getOrNull(1)
    }

    private fun lookupCountryIsCountry(ip: String): String? {
        val body = fetchGeoJson("https://api.country.is/$ip") ?: return null
        return COUNTRY_IS_CODE_REGEX.find(body)?.groupValues?.getOrNull(1)
    }

    private fun fetchGeoJson(url: String): String? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = GEO_TIMEOUT_MS
            readTimeout = GEO_TIMEOUT_MS
            requestMethod = "GET"
            useCaches = false
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "ArabVPN/1.3 Android")
        }
        return try {
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.bufferedReader().use {
                it.readText().take(MAX_GEO_RESPONSE_CHARS)
            }
        } catch (_: Throwable) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun resolvePublicAddress(host: String): InetAddress? = runCatching {
        InetAddress.getAllByName(host).firstOrNull { address ->
            !address.isAnyLocalAddress &&
                !address.isLoopbackAddress &&
                !address.isLinkLocalAddress &&
                !address.isSiteLocalAddress &&
                !address.isMulticastAddress
        }
    }.getOrNull()

    private fun protocolPreflight(candidate: FreeVpnCandidate): Long? = when (candidate.protocol) {
        ProxyProtocol.HTTP -> httpConnectPreflight(candidate)
        ProxyProtocol.SOCKS5 -> socks5Preflight(candidate)
        ProxyProtocol.SOCKS4 -> socks4Preflight(candidate)
        ProxyProtocol.HYSTERIA2, ProxyProtocol.TUIC -> udpEndpointPreflight(candidate.server, candidate.port)
        else -> tcpPreflight(candidate.server, candidate.port)
    }

    private fun udpEndpointPreflight(host: String, port: Int): Long? {
        if (port !in 1..65535) return null
        val started = System.nanoTime()
        return resolvePublicAddress(host)?.let { elapsedMs(started) }
    }

    private fun tcpPreflight(host: String, port: Int): Long? {
        val started = System.nanoTime()
        return runCatching {
            Socket().use { socket ->
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, port), PREFLIGHT_TIMEOUT_MS)
            }
            elapsedMs(started)
        }.getOrNull()
    }

    private fun httpConnectPreflight(candidate: FreeVpnCandidate): Long? {
        val started = System.nanoTime()
        return runCatching {
            Socket().use { socket ->
                socket.soTimeout = PROTOCOL_PREFLIGHT_TIMEOUT_MS
                socket.connect(InetSocketAddress(candidate.server, candidate.port), PREFLIGHT_TIMEOUT_MS)
                val output = BufferedOutputStream(socket.getOutputStream())
                output.write(
                    ("CONNECT 1.1.1.1:443 HTTP/1.1\r\n" +
                        "Host: 1.1.1.1:443\r\n" +
                        "Proxy-Connection: keep-alive\r\n\r\n")
                        .toByteArray(StandardCharsets.US_ASCII)
                )
                output.flush()
                val input = socket.getInputStream().bufferedReader(StandardCharsets.US_ASCII)
                val status = input.readLine().orEmpty()
                require(status.startsWith("HTTP/", ignoreCase = true))
                val code = status.split(' ').getOrNull(1)?.toIntOrNull()
                    ?: error("invalid HTTP proxy response")
                require(code in 200..299) { "HTTP proxy rejected CONNECT" }
            }
            elapsedMs(started)
        }.getOrNull()
    }

    private fun socks5Preflight(candidate: FreeVpnCandidate): Long? {
        val started = System.nanoTime()
        return runCatching {
            Socket().use { socket ->
                socket.soTimeout = PROTOCOL_PREFLIGHT_TIMEOUT_MS
                socket.connect(InetSocketAddress(candidate.server, candidate.port), PREFLIGHT_TIMEOUT_MS)
                val output = BufferedOutputStream(socket.getOutputStream())
                val input = BufferedInputStream(socket.getInputStream())
                output.write(byteArrayOf(0x05, 0x01, 0x00))
                output.flush()
                require(input.read() == 0x05 && input.read() == 0x00) {
                    "SOCKS5 authentication unsupported"
                }
                output.write(
                    byteArrayOf(
                        0x05, 0x01, 0x00, 0x01,
                        0x01, 0x01, 0x01, 0x01,
                        0x01, 0xBB.toByte(),
                    )
                )
                output.flush()
                require(input.read() == 0x05 && input.read() == 0x00) { "SOCKS5 CONNECT failed" }
            }
            elapsedMs(started)
        }.getOrNull()
    }

    private fun socks4Preflight(candidate: FreeVpnCandidate): Long? {
        val started = System.nanoTime()
        return runCatching {
            Socket().use { socket ->
                socket.soTimeout = PROTOCOL_PREFLIGHT_TIMEOUT_MS
                socket.connect(InetSocketAddress(candidate.server, candidate.port), PREFLIGHT_TIMEOUT_MS)
                val output = BufferedOutputStream(socket.getOutputStream())
                val input = BufferedInputStream(socket.getInputStream())
                output.write(
                    byteArrayOf(
                        0x04, 0x01, 0x01, 0xBB.toByte(),
                        0x01, 0x01, 0x01, 0x01, 0x00,
                    )
                )
                output.flush()
                require(input.read() in setOf(0x00, 0x04) && input.read() == 0x5A) {
                    "SOCKS4 CONNECT failed"
                }
            }
            elapsedMs(started)
        }.getOrNull()
    }

    private fun elapsedMs(started: Long): Long = (System.nanoTime() - started) / 1_000_000L

    private fun FreeVpnCandidate.isSafePublicEndpoint(): Boolean {
        if (server.isBlank() || port !in 1..65535) return false
        if (server.equals("localhost", ignoreCase = true)) return false
        val literalIp = runCatching { InetAddress.getByName(server) }.getOrNull()
        if (literalIp != null) {
            if (
                literalIp.isAnyLocalAddress ||
                literalIp.isLoopbackAddress ||
                literalIp.isLinkLocalAddress ||
                literalIp.isSiteLocalAddress ||
                literalIp.isMulticastAddress
            ) return false
        }
        return true
    }

    private fun protocolPriority(protocol: ProxyProtocol): Int = when (protocol) {
        ProxyProtocol.SOCKS5 -> 0
        ProxyProtocol.HTTP -> 1
        ProxyProtocol.SOCKS4 -> 2
        ProxyProtocol.HYSTERIA2 -> 3
        ProxyProtocol.TUIC -> 4
        ProxyProtocol.VLESS -> 5
        ProxyProtocol.TROJAN -> 6
        ProxyProtocol.SHADOWSOCKS -> 7
        ProxyProtocol.VMESS -> 8
    }

    private fun <T> roundRobin(groups: List<List<T>>): List<T> = buildList {
        var index = 0
        while (true) {
            var added = false
            groups.forEach { group ->
                if (index < group.size) {
                    add(group[index])
                    added = true
                }
            }
            if (!added) break
            index++
        }
    }

    private data class CatalogSource(
        val id: String,
        val url: String,
        val linePrefix: String? = null,
    ) {
        fun normalize(line: String): String {
            if (linePrefix == null || "://" in line) return line
            return "$linePrefix$line"
        }
    }

    companion object {
        private const val FETCH_TIMEOUT_MS = 6_000
        private const val GEO_TIMEOUT_MS = 2_800
        private const val PREFLIGHT_TIMEOUT_MS = 2_000
        private const val PROTOCOL_PREFLIGHT_TIMEOUT_MS = 2_800
        private const val MAX_SOURCE_BYTES = 4L * 1024L * 1024L
        private const val MAX_GEO_RESPONSE_CHARS = 8_192
        private const val MAX_PER_SOURCE = 100
        private const val MAX_PREFLIGHT_CANDIDATES = 240
        private const val MAX_GEO_CANDIDATES = 56
        private const val MAX_GEO_CONCURRENCY = 5
        private const val MAX_PREFLIGHT_CONCURRENCY = 20
        const val MAX_CONNECT_ATTEMPTS = 24

        private val IPWHO_COUNTRY_CODE_REGEX =
            Regex("\\\"country_code\\\"\\s*:\\s*\\\"([A-Za-z]{2})\\\"")
        private val COUNTRY_IS_CODE_REGEX =
            Regex("\\\"country\\\"\\s*:\\s*\\\"([A-Za-z]{2})\\\"")
    }
}

/** Pure policy so disagreements between geo providers are regression-testable. */
internal object CountryVerificationPolicy {
    fun accept(expected: String, providerA: String?, providerB: String?): Boolean {
        val expectedCode = expected.trim().uppercase()
        val reports = listOfNotNull(providerA, providerB)
            .map { it.trim().uppercase() }
            .filter { it.length == 2 }
        if (reports.isEmpty()) return false
        if (reports.any { it != expectedCode }) return false
        return reports.any { it == expectedCode }
    }
}

data class FreeVpnCandidate(
    val protocol: ProxyProtocol,
    val server: String,
    val port: Int,
    val outboundJson: String,
    val sourceId: String,
    val displayName: String,
    val preflightLatencyMs: Long? = null,
    val countryEvidence: CountryEvidence = CountryEvidence.UNKNOWN,
) {
    val fingerprint: String
        get() = "${protocol.name}|${server.lowercase()}|$port|$outboundJson"
}

enum class CountryEvidence {
    UNKNOWN,
    SOURCE_COUNTRY_CLAIM,
    ENDPOINT_GEO_VERIFIED,
}

enum class ProxyProtocol {
    HYSTERIA2,
    TUIC,
    TROJAN,
    VLESS,
    SHADOWSOCKS,
    VMESS,
    SOCKS5,
    HTTP,
    SOCKS4,
}
