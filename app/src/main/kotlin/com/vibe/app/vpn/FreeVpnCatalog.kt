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
 * Discovers free public relays, but never trusts a filename/remark as proof of the exit country.
 *
 * There are two evidence levels:
 * 1) LIVE_COUNTRY_API: a live provider currently reports the proxy in the selected country.
 * 2) LABEL_ONLY: community V2Ray feeds that merely place a config under a country filename.
 *    These are endpoint-geolocated before they are even allowed into the expensive tunnel stage.
 *
 * The final full-device connection still has to pass ConnectionQualityClient.verify(country), so
 * even a bad/stale provider classification cannot be presented to the user as a successful VPN.
 */
class FreeVpnCatalog {
    suspend fun discover(country: VpnCountry): List<FreeVpnCandidate> = withContext(Dispatchers.IO) {
        val sources = sourcesFor(country)

        val sourceCandidates = coroutineScope {
            sources.map { source ->
                async(Dispatchers.IO) {
                    runCatching { fetch(source.url) }
                        .getOrNull()
                        .orEmpty()
                        .lineSequence()
                        .map(String::trim)
                        .filter { line -> line.isNotEmpty() && !line.startsWith('#') }
                        .mapNotNull { line -> ProxyShareParser.parse(line, source.id) }
                        .filter { candidate -> candidate.isSafePublicEndpoint() }
                        .map { candidate -> candidate.copy(countryEvidence = source.countryEvidence) }
                        .distinctBy { candidate -> candidate.fingerprint }
                        .take(MAX_PER_SOURCE)
                        .toList()
                }
            }.awaitAll()
        }

        val raw = roundRobin(sourceCandidates)
            .distinctBy { candidate -> candidate.fingerprint }
            .take(MAX_PREFLIGHT_CANDIDATES)

        val geoBudget = Semaphore(MAX_GEO_CONCURRENCY)
        val geoChecked = coroutineScope {
            var labelBudgetUsed = 0
            raw.map { candidate ->
                val shouldGeoCheck = candidate.countryEvidence == CountryEvidence.LABEL_ONLY &&
                    labelBudgetUsed++ < MAX_LABEL_GEO_CANDIDATES
                if (!shouldGeoCheck) {
                    async(Dispatchers.IO) {
                        if (candidate.countryEvidence == CountryEvidence.LABEL_ONLY) null else candidate
                    }
                } else {
                    async(Dispatchers.IO) {
                        geoBudget.withPermit {
                            candidate.takeIf { endpointCountryMatches(it, country) }
                                ?.copy(countryEvidence = CountryEvidence.ENDPOINT_GEO_VERIFIED)
                        }
                    }
                }
            }.awaitAll().filterNotNull()
        }

        val limiter = Semaphore(MAX_PREFLIGHT_CONCURRENCY)
        val preflighted = coroutineScope {
            geoChecked.map { candidate ->
                async(Dispatchers.IO) {
                    limiter.withPermit {
                        candidate.copy(preflightLatencyMs = protocolPreflight(candidate))
                    }
                }
            }.awaitAll()
        }

        preflighted
            .filter { it.preflightLatencyMs != null }
            .sortedWith(
                compareBy<FreeVpnCandidate> { evidencePriority(it.countryEvidence) }
                    .thenBy { protocolPriority(it.protocol) }
                    .thenBy { it.preflightLatencyMs ?: Long.MAX_VALUE }
            )
            .take(MAX_CONNECT_ATTEMPTS)
    }

    private fun sourcesFor(country: VpnCountry): List<CatalogSource> {
        val upperCode = country.code.uppercase()
        val lowerCode = country.code.lowercase()
        val countryName = country.displayNameEn
        return listOf(
            CatalogSource(
                id = "proxyscrape-live-country",
                url = "https://api.proxyscrape.com/v4/free-proxy-list/get?request=displayproxies&proxy_format=protocolipport&format=text&country=$lowerCode&timeout=10000",
                countryEvidence = CountryEvidence.LIVE_COUNTRY_API,
            ),
            CatalogSource(
                id = "proxyscrape-country-mirror",
                url = "https://raw.githubusercontent.com/ProxyScrape/free-proxy-list/main/proxies/countries/$lowerCode/data.txt",
                countryEvidence = CountryEvidence.LIVE_COUNTRY_API,
            ),
            CatalogSource(
                id = "argh73-country",
                url = "https://raw.githubusercontent.com/Argh73/VpnConfigCollector/refs/heads/main/Splitted-By-Country/$countryName.txt",
                countryEvidence = CountryEvidence.LABEL_ONLY,
            ),
            CatalogSource(
                id = "argh94-country",
                url = "https://raw.githubusercontent.com/Argh94/V2RayAutoConfig/main/configs/$countryName.txt",
                countryEvidence = CountryEvidence.LABEL_ONLY,
            ),
            CatalogSource(
                id = "collector-country",
                url = "https://raw.githubusercontent.com/217CnoC/configs-collector-v2ray/main/sub/countries/$upperCode.txt",
                countryEvidence = CountryEvidence.LABEL_ONLY,
            ),
            CatalogSource(
                id = "proxifly-country",
                url = "https://raw.githubusercontent.com/proxifly/free-proxy-list/main/proxies/countries/$upperCode/data.txt",
                countryEvidence = CountryEvidence.LIVE_COUNTRY_API,
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
            setRequestProperty("Accept", "text/plain,application/json;q=0.8,*/*;q=0.2")
            setRequestProperty("User-Agent", "ArabVPN/1.1 Android")
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

    private fun endpointCountryMatches(candidate: FreeVpnCandidate, country: VpnCountry): Boolean {
        val address = resolvePublicAddress(candidate.server) ?: return false
        val connection = (URL("https://ipwho.is/${address.hostAddress}").openConnection() as HttpURLConnection).apply {
            connectTimeout = GEO_TIMEOUT_MS
            readTimeout = GEO_TIMEOUT_MS
            requestMethod = "GET"
            useCaches = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "ArabVPN/1.1 Android")
        }
        return try {
            if (connection.responseCode !in 200..299) return false
            val body = connection.inputStream.bufferedReader().use { it.readText().take(MAX_GEO_RESPONSE_CHARS) }
            val reported = COUNTRY_CODE_REGEX.find(body)?.groupValues?.getOrNull(1)
            reported.equals(country.code, ignoreCase = true)
        } catch (_: Throwable) {
            false
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
        // Hysteria2 and TUIC are QUIC/UDP protocols. A TCP connect test would reject every valid
        // node by definition. DNS/public-endpoint resolution is the safe cheap preflight; libbox
        // config validation + the time-bounded real tunnel attempt performs the protocol handshake.
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
                val code = status.split(' ').getOrNull(1)?.toIntOrNull() ?: error("invalid HTTP proxy response")
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
                require(input.read() == 0x05 && input.read() == 0x00) { "SOCKS5 authentication unsupported" }
                output.write(byteArrayOf(0x05, 0x01, 0x00, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0xBB.toByte()))
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
                output.write(byteArrayOf(0x04, 0x01, 0x01, 0xBB.toByte(), 0x01, 0x01, 0x01, 0x01, 0x00))
                output.flush()
                require(input.read() in setOf(0x00, 0x04) && input.read() == 0x5A) { "SOCKS4 CONNECT failed" }
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

    private fun evidencePriority(evidence: CountryEvidence): Int = when (evidence) {
        CountryEvidence.LIVE_COUNTRY_API -> 0
        CountryEvidence.ENDPOINT_GEO_VERIFIED -> 1
        CountryEvidence.LABEL_ONLY -> 2
        CountryEvidence.UNKNOWN -> 3
    }

    private fun protocolPriority(protocol: ProxyProtocol): Int = when (protocol) {
        ProxyProtocol.HYSTERIA2 -> 0
        ProxyProtocol.TUIC -> 1
        ProxyProtocol.VLESS -> 2
        ProxyProtocol.TROJAN -> 3
        ProxyProtocol.SHADOWSOCKS -> 4
        ProxyProtocol.VMESS -> 5
        ProxyProtocol.SOCKS5 -> 6
        ProxyProtocol.HTTP -> 7
        ProxyProtocol.SOCKS4 -> 8
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
        val countryEvidence: CountryEvidence,
    )

    companion object {
        private const val FETCH_TIMEOUT_MS = 7_000
        private const val GEO_TIMEOUT_MS = 2_500
        private const val PREFLIGHT_TIMEOUT_MS = 2_000
        private const val PROTOCOL_PREFLIGHT_TIMEOUT_MS = 2_500
        private const val MAX_SOURCE_BYTES = 4L * 1024L * 1024L
        private const val MAX_GEO_RESPONSE_CHARS = 8_192
        private const val MAX_PER_SOURCE = 80
        private const val MAX_PREFLIGHT_CANDIDATES = 220
        private const val MAX_LABEL_GEO_CANDIDATES = 48
        private const val MAX_GEO_CONCURRENCY = 6
        private const val MAX_PREFLIGHT_CONCURRENCY = 18
        const val MAX_CONNECT_ATTEMPTS = 24
        private val COUNTRY_CODE_REGEX = Regex("\\\"country_code\\\"\\s*:\\s*\\\"([A-Za-z]{2})\\\"")
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
    LABEL_ONLY,
    LIVE_COUNTRY_API,
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
