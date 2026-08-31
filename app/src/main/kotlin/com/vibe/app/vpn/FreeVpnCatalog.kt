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
                compareBy<FreeVpnCandidate> { it.preflightLatencyMs ?: Long.MAX_VALUE }
                    .thenBy { protocolPriority(it.protocol) }
            )
            .take(MAX_CONNECT_ATTEMPTS)
    }

    private fun sourcesFor(country: VpnCountry): List<CatalogSource> {
        val code = country.code.uppercase()
        return buildList {
            // Fresh country-classified feed. Missing country files simply return 404 and are skipped.
            add(
                CatalogSource(
                    id = "argh94-country",
                    url = "https://raw.githubusercontent.com/Argh94/V2RayAutoConfig/main/configs/${country.displayNameEn}.txt",
                )
            )

            // Secondary country feed. It is less fresh, so it is never trusted without post-connect geo proof.
            add(
                CatalogSource(
                    id = "collector-country",
                    url = "https://raw.githubusercontent.com/217CnoC/configs-collector-v2ray/main/sub/countries/$code.txt",
                )
            )

            // For Egypt, the fresh feed currently has direct Cairo candidates. Keep a second explicit
            // path as redundancy in case the first repository layout changes.
            if (country == VpnCountry.EGYPT) {
                add(
                    CatalogSource(
                        id = "argh94-egypt-fallback",
                        url = "https://raw.githubusercontent.com/Argh94/V2RayAutoConfig/main/configs/Egypt.txt",
                    )
                )
            }
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

    private fun protocolPriority(protocol: ProxyProtocol): Int = when (protocol) {
        ProxyProtocol.TROJAN -> 0
        ProxyProtocol.VLESS -> 1
        ProxyProtocol.SHADOWSOCKS -> 2
        ProxyProtocol.VMESS -> 3
    }

    private data class CatalogSource(val id: String, val url: String)

    companion object {
        private const val FETCH_TIMEOUT_MS = 7_000
        private const val PREFLIGHT_TIMEOUT_MS = 1_800
        private const val MAX_SOURCE_BYTES = 2L * 1024L * 1024L
        private const val MAX_PER_SOURCE = 100
        private const val MAX_PREFLIGHT_CANDIDATES = 40
        const val MAX_CONNECT_ATTEMPTS = 8
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
}
