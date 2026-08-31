package com.vibe.app.vpn

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToLong

/**
 * Post-connect quality gate.
 *
 * A tunnel is never reported as connected merely because the VPN interface is UP.
 * We require two independent geo signals to agree with the selected country, then
 * measure real HTTPS latency and downstream throughput through the tunnel.
 */
class ConnectionQualityClient(
    private val ipLocationClient: IpLocationClient = IpLocationClient(),
) {
    suspend fun verify(expectedCountry: VpnCountry): ConnectionQualityReport = withContext(Dispatchers.IO) {
        val primary = ipLocationClient.check()
        require(primary.countryCode.equals(expectedCountry.code, ignoreCase = true)) {
            "عنوان الخروج ليس من ${expectedCountry.displayNameAr}. ظهر من ${primary.country.ifBlank { primary.countryCode.ifBlank { "دولة أخرى" } }}."
        }

        val secondary = readCloudflareTrace()
        require(secondary.countryCode.equals(expectedCountry.code, ignoreCase = true)) {
            "فشل التحقق المزدوج من الدولة: Cloudflare اكتشف ${secondary.countryCode.ifBlank { "دولة أخرى" }} بدل ${expectedCountry.code}."
        }
        if (primary.ip.isNotBlank() && secondary.ip.isNotBlank()) {
            require(primary.ip == secondary.ip) {
                "نتائج فحص عنوان IP غير متطابقة؛ تم رفض الاتصال احتياطياً."
            }
        }

        val latencySamples = List(LATENCY_SAMPLES) { measureLatencyMs() }.sorted()
        val medianLatency = latencySamples[latencySamples.size / 2]
        require(medianLatency <= MAX_MEDIAN_LATENCY_MS) {
            "الخادم بطيء: زمن الاستجابة ${medianLatency}ms ويتجاوز الحد المسموح ${MAX_MEDIAN_LATENCY_MS}ms."
        }

        val downloadMbps = measureDownloadMbps()
        require(downloadMbps >= MIN_DOWNLOAD_MBPS) {
            "سرعة الخادم منخفضة: ${formatMbps(downloadMbps)} Mbps والحد الأدنى ${formatMbps(MIN_DOWNLOAD_MBPS)} Mbps."
        }

        ConnectionQualityReport(
            ipLocation = primary,
            secondaryCountryCode = secondary.countryCode,
            medianLatencyMs = medianLatency,
            downloadMbps = downloadMbps,
            geoVerified = true,
        )
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

    private fun formatMbps(value: Double): String = "%.1f".format(java.util.Locale.US, value)

    private data class TraceResult(
        val ip: String,
        val countryCode: String,
    )

    companion object {
        // Strict enough to reject obviously poor public relays while remaining realistic
        // for Saudi Arabia -> North Africa / Levant routing.
        const val MAX_MEDIAN_LATENCY_MS = 300L
        const val MIN_DOWNLOAD_MBPS = 5.0

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
)
