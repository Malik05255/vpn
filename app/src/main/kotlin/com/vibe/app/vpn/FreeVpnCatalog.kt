package com.vibe.app.vpn

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import kotlin.math.min

/**
 * Downloads public, zero-cost proxy/VPN candidates from multiple independent feeds.
 *
 * Feed labels are NEVER treated as proof of geography. They are only discovery hints.
 * Every candidate must later pass ConnectionQualityClient after the full-device VPN is up.
 */
class FreeVpnCatalog {
    suspend fun discover(country: VpnCountry): List<FreeVpnCandidate> = withContext(Dispatchers.IO) {
        val sources = sourcesFor(country)
        val raw = sources.flatMap { source ->
            runCatching { fetch(source.url) }
                .getOrNull()
                .orEmpty()
                .lineSequence()
                .map(String::trim)
                .filter { line -> line.isNotEmpty() && !line.startsWith('#') }
                .mapNotNull { line -> ProxyShareParser.parse(line, source.id) }
                .filter { candidate -> candidate.isSafePublicEndpoint() }
                .take(MAX_PER_SOURCE)
                .toList()
        }.distinctBy { candidate -> candidate.fingerprint }

        // A cheap TCP preflight avoids spending seconds creating a VPN for dead endpoints.
        val preflighted = coroutineScope {
            raw.take(MAX_PREFLIGHT_CANDIDATES).map { candidate ->
                async(Dispatchers.IO) {
                    candidate.copy(preflightLatencyMs = tcpPreflight(candidate.server, candidate.port))
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
        return buildList {
            // Country-classified V2Ray/Trojan feed. Geography is still verified after connection.
            add(
                CatalogSource(
                    id = "argh94-country",
                    url = "https://raw.githubusercontent.com/Argh94/V2RayAutoConfig/main/configs/${country.displayNameEn}.txt",
                )
            )

            // Older V2Ray country feed retained as an independent fallback.
            add(
                CatalogSource(
                    id = "collector-country",
                    url = "https://raw.githubusercontent.com/217CnoC/configs-collector-v2ray/main/sub/countries/$upperCode.txt",
                )
            )

            // Frequently refreshed mixed-protocol country pool. These feeds are especially useful
            // in countries where public V2Ray nodes are scarce (Egypt currently falls in this case).
            add(
                CatalogSource(
                    id = "proxyscrape-country",
                    url = "https://raw.githubusercontent.com/ProxyScrape/free-proxy-list/main/proxies/countries/$lowerCode/data.txt",
                )
            )
            add(
                CatalogSource(
                    id = "proxifly-country",
                    url = "https://raw.githubusercontent.com/proxifly/free-proxy-list/main/proxies/countries/$upperCode/data.txt",
                )
            )
        }
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

    private fun tcpPreflight(host: String, port: Int): Long? {
        val started = System.nanoTime()
        return runCatching {
            Socket().use { socket ->
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, port), PREFLIGHT_TIMEOUT_MS)
            }
            (System.nanoTime() - started) / 1_000_000L
        }.getOrNull()
    }

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

    /**
     * Prefer VPN-native protocols. HTTP/SOCKS are deliberately last-resort fallbacks because
     * free public pools for some Arab countries expose far more of those endpoints.
     */
    private fun protocolPriority(protocol: ProxyProtocol): Int = when (protocol) {
        ProxyProtocol.TROJAN -> 0
        ProxyProtocol.VLESS -> 1
        ProxyProtocol.SHADOWSOCKS -> 2
        ProxyProtocol.VMESS -> 3
        ProxyProtocol.SOCKS5 -> 4
        ProxyProtocol.HTTP -> 5
        ProxyProtocol.SOCKS4 -> 6
    }

    private data class CatalogSource(val id: String, val url: String)

    companion object {
        private const val FETCH_TIMEOUT_MS = 7_000
        private const val PREFLIGHT_TIMEOUT_MS = 1_800
        private const val MAX_SOURCE_BYTES = 2L * 1024L * 1024L
        private const val MAX_PER_SOURCE = 40
        private const val MAX_PREFLIGHT_CANDIDATES = 120
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
