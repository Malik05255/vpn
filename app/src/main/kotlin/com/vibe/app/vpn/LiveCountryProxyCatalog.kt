package com.vibe.app.vpn

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import kotlin.math.min

/**
 * High-confidence public proxy discovery for the selected country.
 *
 * Unlike endpoint geolocation, this class verifies the IP seen by external services while the
 * request itself is travelling THROUGH the candidate proxy. Only HTTP and SOCKS5 are used here
 * because the JVM proxy stack can exercise their real CONNECT path before sing-box is started.
 */
internal class LiveCountryProxyCatalog {
    suspend fun discover(country: VpnCountry): List<FreeVpnCandidate> = withContext(Dispatchers.IO) {
        val groups = coroutineScope {
            sourcesFor(country).map { source ->
                async(Dispatchers.IO) {
                    val body = runCatching { fetch(source.url) }.getOrNull().orEmpty()
                    source.lines(body)
                        .map(String::trim)
                        .filter { it.isNotEmpty() && !it.startsWith('#') }
                        .map(source::normalize)
                        .mapNotNull { ProxyShareParser.parse(it, source.id) }
                        .filter { it.protocol == ProxyProtocol.HTTP || it.protocol == ProxyProtocol.SOCKS5 }
                        .filter(::isSafePublicEndpoint)
                        .distinctBy { it.fingerprint }
                        .take(MAX_PER_SOURCE)
                        .toList()
                }
            }.awaitAll()
        }

        val raw = roundRobin(groups)
            .distinctBy { it.fingerprint }
            .take(MAX_RAW_CANDIDATES)

        val limiter = Semaphore(MAX_VERIFY_CONCURRENCY)
        coroutineScope {
            raw.map { candidate ->
                async(Dispatchers.IO) {
                    limiter.withPermit { verifyRealExit(candidate, country) }
                }
            }.awaitAll()
                .filterNotNull()
                .sortedBy { it.preflightLatencyMs ?: Long.MAX_VALUE }
                .take(MAX_RESULTS)
        }
    }

    private fun verifyRealExit(
        candidate: FreeVpnCandidate,
        country: VpnCountry,
    ): FreeVpnCandidate? {
        val started = System.nanoTime()
        val expected = country.code.uppercase()

        val first = countryThroughProxy(
            candidate = candidate,
            url = "https://api.country.is/",
            regex = COUNTRY_IS_REGEX,
        ) ?: return null
        if (!first.equals(expected, ignoreCase = true)) return null

        val second = countryThroughProxy(
            candidate = candidate,
            url = "https://ipwho.is/",
            regex = IPWHO_REGEX,
        ) ?: return null
        if (!second.equals(expected, ignoreCase = true)) return null

        return candidate.copy(
            preflightLatencyMs = (System.nanoTime() - started) / 1_000_000L,
            countryEvidence = CountryEvidence.ENDPOINT_GEO_VERIFIED,
        )
    }

    private fun countryThroughProxy(
        candidate: FreeVpnCandidate,
        url: String,
        regex: Regex,
    ): String? {
        val proxyType = when (candidate.protocol) {
            ProxyProtocol.HTTP -> Proxy.Type.HTTP
            ProxyProtocol.SOCKS5 -> Proxy.Type.SOCKS
            else -> return null
        }
        val proxy = Proxy(
            proxyType,
            InetSocketAddress(candidate.server, candidate.port),
        )
        val connection = (URL(url).openConnection(proxy) as HttpURLConnection).apply {
            connectTimeout = PROXY_CONNECT_TIMEOUT_MS
            readTimeout = PROXY_READ_TIMEOUT_MS
            requestMethod = "GET"
            useCaches = false
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Connection", "close")
            setRequestProperty("User-Agent", "ArabVPN/1.4 Android")
        }
        return try {
            if (connection.responseCode !in 200..299) return null
            val body = connection.inputStream.bufferedReader().use { reader ->
                val text = reader.readText()
                text.take(MAX_RESPONSE_CHARS)
            }
            regex.find(body)?.groupValues?.getOrNull(1)
        } catch (_: Throwable) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun sourcesFor(country: VpnCountry): List<Source> {
        val upper = country.code.uppercase()
        val lower = country.code.lowercase()
        val proxyScrape =
            "https://api.proxyscrape.com/v4/free-proxy-list/get?request=displayproxies" +
                "&proxy_format=protocolipport&format=text&country=$lower&timeout=10000"
        val hproxy = "https://hproxy.com/api/proxy-list?format=txt&country=$upper&limit=100"

        return listOf(
            Source(
                id = "real-exit-proxyscrape-http",
                url = "$proxyScrape&protocol=http",
                prefix = "http://",
            ),
            Source(
                id = "real-exit-proxyscrape-socks5",
                url = "$proxyScrape&protocol=socks5",
                prefix = "socks5://",
            ),
            Source(
                id = "real-exit-hproxy-http",
                url = "$hproxy&protocol=http",
                prefix = "http://",
            ),
            Source(
                id = "real-exit-hproxy-socks5",
                url = "$hproxy&protocol=socks5",
                prefix = "socks5://",
            ),
            Source(
                id = "real-exit-socks5proxies",
                url = "https://api.socks5proxies.com/api/proxies?country=$upper&limit=100",
                jsonFeed = true,
            ),
            Source(
                id = "real-exit-proxyscrape-snapshot",
                url = "https://raw.githubusercontent.com/ProxyScrape/free-proxy-list/main/proxies/countries/$lower/data.txt",
            ),
            Source(
                id = "real-exit-proxifly-snapshot",
                url = "https://raw.githubusercontent.com/proxifly/free-proxy-list/main/proxies/countries/$upper/data.txt",
            ),
        )
    }

    private fun fetch(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = SOURCE_TIMEOUT_MS
            readTimeout = SOURCE_TIMEOUT_MS
            requestMethod = "GET"
            useCaches = false
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json,text/plain,*/*;q=0.2")
            setRequestProperty("User-Agent", "ArabVPN/1.4 Android")
        }
        return try {
            if (connection.responseCode !in 200..299) return ""
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
        } catch (_: Throwable) {
            ""
        } finally {
            connection.disconnect()
        }
    }

    private fun isSafePublicEndpoint(candidate: FreeVpnCandidate): Boolean {
        if (candidate.server.isBlank() || candidate.port !in 1..65535) return false
        if (candidate.server.equals("localhost", ignoreCase = true)) return false
        val literal = runCatching { InetAddress.getByName(candidate.server) }.getOrNull()
        if (literal != null) {
            if (
                literal.isAnyLocalAddress ||
                literal.isLoopbackAddress ||
                literal.isLinkLocalAddress ||
                literal.isSiteLocalAddress ||
                literal.isMulticastAddress
            ) return false
        }
        return true
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

    private data class Source(
        val id: String,
        val url: String,
        val prefix: String? = null,
        val jsonFeed: Boolean = false,
    ) {
        fun lines(body: String): Sequence<String> = if (jsonFeed) {
            Socks5ProxiesPublicFeed.shareLines(body)
        } else {
            body.lineSequence()
        }

        fun normalize(line: String): String {
            if (prefix == null || "://" in line) return line
            return "$prefix$line"
        }
    }

    private companion object {
        const val SOURCE_TIMEOUT_MS = 6_000
        const val PROXY_CONNECT_TIMEOUT_MS = 4_000
        const val PROXY_READ_TIMEOUT_MS = 4_500
        const val MAX_SOURCE_BYTES = 4L * 1024L * 1024L
        const val MAX_RESPONSE_CHARS = 8_192
        const val MAX_PER_SOURCE = 100
        const val MAX_RAW_CANDIDATES = 180
        const val MAX_VERIFY_CONCURRENCY = 14
        const val MAX_RESULTS = 16

        val COUNTRY_IS_REGEX = Regex("\\\"country\\\"\\s*:\\s*\\\"([A-Za-z]{2})\\\"")
        val IPWHO_REGEX = Regex("\\\"country_code\\\"\\s*:\\s*\\\"([A-Za-z]{2})\\\"")
    }
}
