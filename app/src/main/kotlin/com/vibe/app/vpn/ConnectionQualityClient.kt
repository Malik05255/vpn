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
 * Country identity is a hard requirement: both independent geo signals must match. Public relay
 * performance is volatile, so latency/throughput are measured and reported but no longer turn a
 * country-correct tunnel into a false "no connection" result.
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

    private suspend fun verifyGeoWithWarmup(expectedCountry: VpnCountry): Pair<IpLocation, TraceResult> {
        var lastError: Throwable? = null
        repeat(GEO_WARMUP_ATTEMPTS) { attemptIndex ->
            val attempt = runCatching {
                val primary = ipLocationClient.check()
                require(primary.countryCode.equals(expectedCountry.code, ignoreCase = true)) {
                    "عنوان الخروج ليس من ${expectedCountry.displayNameAr}. ظهر من ${primary.country.ifBlank { primary.countryCode.ifBlank { "دولة أخرى" } }}."
                }

                val secondary = readCloudflareTrace()
                require(secondary.countryCode.equals(expectedCountry.code, ignoreCase = true)) {
                    "فشل التحقق المزدوج من الدولة: Cloudflare اكتشف ${secondary.countryCode.ifBlank { "دولة أخرى" }} بدل ${expectedCountry.code}."
                }
                primary to secondary
            }

            attempt.getOrNull()?.let { return it }
            lastError = attempt.exceptionOrNull()
            if (attemptIndex < GEO_WARMUP_ATTEMPTS - 1) delay(GEO_WARMUP_RETRY_MS)
        }
        throw lastError ?: IllegalStateException("تعذر التحقق من دولة الخروج.")
    }

    private fun readCloudflareTrace(): TraceResult {
        val connection = open(CLOUDFLARE_TRACE_URL, connectTimeoutMs = 7_000, readTimeoutMs = 7_000)
        return try {
            val code = connection.responseCode
            require(code in 200..299) { "Cloudflare geo verification failed with HTTP $code" }
            val values = connection.inputStream.bufferedReader().useLines { lines ->
                lines.mapNotNull { line ->
                    val index = line.indexOf('=')
                    if (index <= 0) null else line.substring(0, index) to line.substring(index + 1)
                }.toMap()
            }
            TraceResult(
                ip = values["ip"].orEmpty(),
                countryCode = values["loc"].orEmpty(),
            )
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
            setRequestProperty("User-Agent", "ArabVPN/1.0 Android")
        }

    private data class TraceResult(
        val ip: String,
        val countryCode: String,
    )

    companion object {
        const val MAX_MEDIAN_LATENCY_MS = 1_200L
        const val MIN_DOWNLOAD_MBPS = 1.0
        const val UNAVAILABLE_LATENCY_MS = -1L
        const val UNAVAILABLE_DOWNLOAD_MBPS = -1.0

        private const val GEO_WARMUP_ATTEMPTS = 5
        private const val GEO_WARMUP_RETRY_MS = 1_000L
        private const val LATENCY_SAMPLES = 3
        private const val LATENCY_TIMEOUT_MS = 4_000
        private const val SPEED_TIMEOUT_MS = 12_000
        private const val SPEED_TEST_BYTES = 512 * 1024L

        private const val CLOUDFLARE_TRACE_URL = "https://www.cloudflare.com/cdn-cgi/trace"
        private const val CLOUDFLARE_204_URL = "https://cp.cloudflare.com/generate_204"
        private const val CLOUDFLARE_SPEED_URL = "https://speed.cloudflare.com/__down"
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
