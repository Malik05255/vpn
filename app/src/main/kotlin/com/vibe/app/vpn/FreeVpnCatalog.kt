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
 * Downloads public, zero-cost proxy/VPN candidates from multiple independent feeds.
 *
 * Feed labels are discovery hints only. Every final connection still has to prove its exit country
 * after the full-device VPN is running.
 */
class FreeVpnCatalog {
    suspend fun discover(country: VpnCountry): List<FreeVpnCandidate> = withContext(Dispatchers.IO) {
        val sources = sourcesFor(country)

        // Fetch independent feeds concurrently. Slow/404 feeds no longer delay every other source.
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
                        .distinctBy { candidate -> candidate.fingerprint }
                        .take(MAX_PER_SOURCE)
                        .toList()
                }
            }.awaitAll()
        }

        // Interleave feeds before the global preflight cap. The previous source-by-source flattening
        // allowed one large feed to consume the whole test budget and starve the other countries'
        // healthier sources.
        val raw = roundRobin(sourceCandidates)
            .distinctBy { candidate -> candidate.fingerprint }
            .take(MAX_PREFLIGHT_CANDIDATES)

        val limiter = Semaphore(MAX_PREFLIGHT_CONCURRENCY)
        val preflighted = coroutineScope {
            raw.map { candidate ->
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
                compareBy<FreeVpnCandidate> { protocolPriority(it.protocol) }
                    .thenBy { it.preflightLatencyMs ?: Long.MAX_VALUE }
            )
            .take(MAX_CONNECT_ATTEMPTS)
    }

    private fun sourcesFor(country: VpnCountry): List<CatalogSource> {
        val upperCode = country.code.uppercase()
        val lowerCode = country.code.lowercase()
        val countryName = country.displayNameEn
        return listOf(
            // Actively maintained multi-source collector. This is important for Jordan where the
            // older feeds frequently have no country file at all.
            CatalogSource(
                id = "argh73-country",
                url = "https://raw.githubusercontent.com/Argh73/VpnConfigCollector/refs/heads/main/Splitted-By-Country/$countryName.txt",
            ),
            CatalogSource(
                id = "argh94-country",
                url = "https://raw.githubusercontent.com/Argh94/V2RayAutoConfig/main/configs/$countryName.txt",
            ),
            CatalogSource(
                id = "collector-country",
                url = "https://raw.githubusercontent.com/217CnoC/configs-collector-v2ray/main/sub/countries/$upperCode.txt",
            ),
            CatalogSource(
                id = "proxyscrape-country",
                url = "https://raw.githubusercontent.com/ProxyScrape/free-proxy-list/main/proxies/countries/$lowerCode/data.txt",
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
            setRequestProperty("Accept", "text/plain")
            setRequestProperty("User-Agent", "ArabVPN/1.0 Android")
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

    private fun protocolPreflight(candidate: FreeVpnCandidate): Long? = when (candidate.protocol) {
        ProxyProtocol.HTTP -> httpConnectPreflight(candidate)
        ProxyProtocol.SOCKS5 -> socks5Preflight(candidate)
        ProxyProtocol.SOCKS4 -> socks4Preflight(candidate)
        else -> tcpPreflight(candidate.server, candidate.port)
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

    /** A TCP-open HTTP proxy is useless if it cannot establish CONNECT to HTTPS. */
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

    private fun protocolPriority(protocol: ProxyProtocol): Int = when (protocol) {
        ProxyProtocol.VLESS -> 0
        ProxyProtocol.TROJAN -> 1
        ProxyProtocol.SHADOWSOCKS -> 2
        ProxyProtocol.VMESS -> 3
        ProxyProtocol.SOCKS5 -> 4
        ProxyProtocol.HTTP -> 5
        ProxyProtocol.SOCKS4 -> 6
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

    private data class CatalogSource(val id: String, val url: String)

    companion object {
        private const val FETCH_TIMEOUT_MS = 7_000
        private const val PREFLIGHT_TIMEOUT_MS = 2_000
        private const val PROTOCOL_PREFLIGHT_TIMEOUT_MS = 2_500
        private const val MAX_SOURCE_BYTES = 4L * 1024L * 1024L
        private const val MAX_PER_SOURCE = 60
        private const val MAX_PREFLIGHT_CANDIDATES = 180
        private const val MAX_PREFLIGHT_CONCURRENCY = 18
        const val MAX_CONNECT_ATTEMPTS = 18
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
) {
    val fingerprint: String
        get() = "${protocol.name}|${server.lowercase()}|$port|$outboundJson"
}

enum class ProxyProtocol {
    TROJAN,
    VLESS,
    SHADOWSOCKS,
    VMESS,
    SOCKS5,
    HTTP,
    SOCKS4,
}
