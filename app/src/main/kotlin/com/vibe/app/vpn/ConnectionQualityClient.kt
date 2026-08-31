package com.vibe.app.vpn

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToLong

/**
 * Post-connect verification gate.
 *
 * The exit country remains a hard requirement, but one stale GeoIP database must not reject a
 * valid tunnel. A connection is accepted only when at least two of three independent observations
 * report the requested country: IpLocationClient (ipwho/ipapi), Cloudflare trace, and country.is.
 */
class ConnectionQualityClient(
    private val ipLocationClient: IpLocationClient = IpLocationClient(),
) {
    suspend fun verify(expectedCountry: VpnCountry): ConnectionQualityReport = withContext(Dispatchers.IO) {
        val (primary, secondary) = verifyGeoWithWarmup(expectedCountry)

        val latencySamples = List(LATENCY_SAMPLES) {
            runCatching { measureLatencyMs() }.getOrNull()
        }.filterNotNull().sorted()
        val medianLatency = latencySamples
            .takeIf { it.isNotEmpty() }
            ?.let { it[it.size / 2] }
            ?: UNAVAILABLE_LATENCY_MS

        val downloadMbps = runCatching { measureDownloadMbps() }
            .getOrDefault(UNAVAILABLE_DOWNLOAD_MBPS)

        ConnectionQualityReport(
            ipLocation = primary,
            secondaryCountryCode = secondary.countryCode,
            medianLatencyMs = medianLatency,
            downloadMbps = downloadMbps,
            geoVerified = true,
            performanceAcceptable =
                medianLatency in 0..MAX_MEDIAN_LATENCY_MS && downloadMbps >= MIN_DOWNLOAD_MBPS,
        )
    }

    private suspend fun verifyGeoWithWarmup(expectedCountry: VpnCountry): Pair<IpLocation, GeoSignal> {
        var lastError: Throwable? = null
        repeat(GEO_WARMUP_ATTEMPTS) { attemptIndex ->
            val attempt = runCatching {
                val primary = ipLocationClient.check(expectedCountry.code)
                val cloudflare = runCatching { readCloudflareTrace() }.getOrNull()
                val countryIs = runCatching { readCountryIs() }.getOrNull()

                val observedCodes = listOfNotNull(
                    primary.countryCode.takeIf(String::isNotBlank),
                    cloudflare?.countryCode?.takeIf(String::isNotBlank),
                    countryIs?.countryCode?.takeIf(String::isNotBlank),
                )
                val matches = observedCodes.count {
                    it.equals(expectedCountry.code, ignoreCase = true)
                }
                require(matches >= REQUIRED_COUNTRY_MATCHES) {
                    "لم يتحقق إجماع دولة الخروج لـ ${expectedCountry.displayNameAr}. " +
                        "الإشارات=${observedCodes.joinToString(",").ifBlank { "غير متاحة" }}"
                }

                // Normally the rich provider matches. If its GeoIP database is the one stale
                // signal while both network observers match, do not use its wrong coordinates for
                // mock-location sync; keep the verified country/IP and let CountryLocationResolver
                // use the country's safe default coordinates.
                val trustedPrimary = if (
                    primary.countryCode.equals(expectedCountry.code, ignoreCase = true)
                ) {
                    primary
                } else {
                    val trustedIp = listOfNotNull(cloudflare, countryIs)
                        .firstOrNull { it.countryCode.equals(expectedCountry.code, ignoreCase = true) }
                        ?.ip
                        .orEmpty()
                    IpLocation(
                        ip = trustedIp.ifBlank { primary.ip },
                        countryCode = expectedCountry.code,
                        country = expectedCountry.displayNameEn,
                    )
                }

                val secondary = listOfNotNull(cloudflare, countryIs)
                    .firstOrNull { it.countryCode.equals(expectedCountry.code, ignoreCase = true) }
                    ?: cloudflare
                    ?: countryIs
                    ?: GeoSignal(ip = primary.ip, countryCode = primary.countryCode)

                trustedPrimary to secondary
            }

            attempt.getOrNull()?.let { return it }
            lastError = attempt.exceptionOrNull()
            if (attemptIndex < GEO_WARMUP_ATTEMPTS - 1) delay(GEO_WARMUP_RETRY_MS)
        }
        throw lastError ?: IllegalStateException("تعذر التحقق من دولة الخروج.")
    }

    private fun readCloudflareTrace(): GeoSignal {
        val connection = open(CLOUDFLARE_TRACE_URL, connectTimeoutMs = GEO_TIMEOUT_MS, readTimeoutMs = GEO_TIMEOUT_MS)
        return try {
            val code = connection.responseCode
            require(code in 200..299) { "Cloudflare geo verification failed with HTTP $code" }
            val values = connection.inputStream.bufferedReader().useLines { lines ->
                lines.mapNotNull { line ->
                    val index = line.indexOf('=')
                    if (index <= 0) null else line.substring(0, index) to line.substring(index + 1)
                }.toMap()
            }
            GeoSignal(
                ip = values["ip"].orEmpty(),
                countryCode = values["loc"].orEmpty(),
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun readCountryIs(): GeoSignal {
        val connection = open(COUNTRY_IS_URL, connectTimeoutMs = GEO_TIMEOUT_MS, readTimeoutMs = GEO_TIMEOUT_MS)
        return try {
            val code = connection.responseCode
            require(code in 200..299) { "country.is verification failed with HTTP $code" }
            val body = connection.inputStream.bufferedReader().use { it.readText().take(8_192) }
            val ip = JSON_IP_REGEX.find(body)?.groupValues?.getOrNull(1).orEmpty()
            val country = JSON_COUNTRY_REGEX.find(body)?.groupValues?.getOrNull(1).orEmpty()
            require(ip.isNotBlank() && country.isNotBlank()) { "country.is returned incomplete geo data" }
            GeoSignal(ip = ip, countryCode = country)
        } finally {
            connection.disconnect()
        }
    }

    private fun measureLatencyMs(): Long {
        val started = System.nanoTime()
        val connection = open(
            "$CLOUDFLARE_204_URL?nonce=${System.nanoTime()}",
            connectTimeoutMs = LATENCY_TIMEOUT_MS,
            readTimeoutMs = LATENCY_TIMEOUT_MS,
        )
        return try {
            val code = connection.responseCode
            require(code == 204 || code in 200..299) { "Latency probe failed with HTTP $code" }
            ((System.nanoTime() - started) / 1_000_000.0).roundToLong()
        } finally {
            connection.disconnect()
        }
    }

    private fun measureDownloadMbps(): Double {
        val connection = open(
            "$CLOUDFLARE_SPEED_URL?bytes=$SPEED_TEST_BYTES&nonce=${System.nanoTime()}",
            connectTimeoutMs = SPEED_TIMEOUT_MS,
            readTimeoutMs = SPEED_TIMEOUT_MS,
        )
        return try {
            val code = connection.responseCode
            require(code in 200..299) { "Speed probe failed with HTTP $code" }
            val started = System.nanoTime()
            var total = 0L
            BufferedInputStream(connection.inputStream, 32 * 1024).use { input ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    total += read
                    if (total >= SPEED_TEST_BYTES) break
                }
            }
            require(total >= SPEED_TEST_BYTES / 2) { "Speed probe returned too little data" }
            val seconds = (System.nanoTime() - started) / 1_000_000_000.0
            require(seconds > 0.0) { "Invalid speed probe duration" }
            (total * 8.0 / 1_000_000.0) / seconds
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String, connectTimeoutMs: Int, readTimeoutMs: Int): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            requestMethod = "GET"
            useCaches = false
            instanceFollowRedirects = true
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("Accept", "*/*")
            setRequestProperty("User-Agent", "ArabVPN/1.2 Android")
        }

    private data class GeoSignal(
        val ip: String,
        val countryCode: String,
    )

    companion object {
        const val MAX_MEDIAN_LATENCY_MS = 1_200L
        const val MIN_DOWNLOAD_MBPS = 1.0
        const val UNAVAILABLE_LATENCY_MS = -1L
        const val UNAVAILABLE_DOWNLOAD_MBPS = -1.0

        private const val REQUIRED_COUNTRY_MATCHES = 2
        private const val GEO_WARMUP_ATTEMPTS = 4
        private const val GEO_WARMUP_RETRY_MS = 900L
        private const val GEO_TIMEOUT_MS = 6_000
        private const val LATENCY_SAMPLES = 3
        private const val LATENCY_TIMEOUT_MS = 4_000
        private const val SPEED_TIMEOUT_MS = 12_000
        private const val SPEED_TEST_BYTES = 512 * 1024L

        private const val CLOUDFLARE_TRACE_URL = "https://www.cloudflare.com/cdn-cgi/trace"
        private const val COUNTRY_IS_URL = "https://api.country.is/"
        private const val CLOUDFLARE_204_URL = "https://cp.cloudflare.com/generate_204"
        private const val CLOUDFLARE_SPEED_URL = "https://speed.cloudflare.com/__down"
        private val JSON_IP_REGEX = Regex("\\\"ip\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
        private val JSON_COUNTRY_REGEX = Regex("\\\"country\\\"\\s*:\\s*\\\"([A-Za-z]{2})\\\"")
    }
}

data class ConnectionQualityReport(
    val ipLocation: IpLocation,
    val secondaryCountryCode: String,
    val medianLatencyMs: Long,
    val downloadMbps: Double,
    val geoVerified: Boolean,
    val performanceAcceptable: Boolean = true,
)
