package com.vibe.app.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Fully automatic connection manager. Every candidate is isolated, time-bounded and completely
 * torn down before the next one starts. A privacy-safe diagnostic trail is kept so a real Android
 * failure can be distinguished from an empty/dead public proxy pool without adb.
 */
class AutomaticVpnManager(context: Context) {
    private val appContext = context.applicationContext
    private val catalog = FreeVpnCatalog()
    private val qualityClient = ConnectionQualityClient()
    private val runtimeDir = File(appContext.filesDir, "vpn-runtime").apply { mkdirs() }

    @Volatile
    private var active = false

    fun preparePermissionIntent(): Intent? = VpnService.prepare(appContext)

    fun diagnosticSummary(): String = VpnDiagnostics.summary(appContext)

    suspend fun connect(
        country: VpnCountry,
        onProgress: (String) -> Unit = {},
    ): AutomaticConnectionResult = withContext(Dispatchers.IO) {
        disconnectInternal()
        VpnDiagnostics.reset(appContext, country)
        VpnDiagnostics.record(appContext, "discovery.start", "country=${country.code}")

        onProgress("جاري جمع واختبار المسارات المجانية في ${country.displayNameAr}…")
        val discovered = runCatching { catalog.discover(country) }
            .onFailure { error ->
                VpnDiagnostics.record(appContext, "discovery.failed", error.userMessage())
            }
            .getOrThrow()

        // Defence in depth: the catalog already performs endpoint geolocation, but the connection
        // boundary independently refuses anything that did not survive that verification. This
        // prevents a future source/parser regression from ever reaching libbox as an untrusted
        // country-labelled candidate.
        val candidates = discovered.filter { candidate ->
            val verified = candidate.countryEvidence == CountryEvidence.ENDPOINT_GEO_VERIFIED
            if (!verified) {
                VpnDiagnostics.record(
                    appContext,
                    "discovery.reject_unverified",
                    "protocol=${candidate.protocol.name}; source=${candidate.sourceId}; evidence=${candidate.countryEvidence}",
                )
            }
            verified
        }
        val failures = mutableListOf<String>()

        val sourceSummary = candidates
            .groupingBy { it.sourceId }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(8)
            .joinToString(",") { "${it.key}:${it.value}" }
        val protocolSummary = candidates
            .groupingBy { it.protocol.name }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .joinToString(",") { "${it.key}:${it.value}" }
        VpnDiagnostics.record(
            appContext,
            "discovery.ready",
            "discovered=${discovered.size}; verified=${candidates.size}; protocols=$protocolSummary; sources=$sourceSummary",
        )

        if (candidates.isNotEmpty()) {
            onProgress("وجدنا ${candidates.size} مسارات حية وموثقة جغرافياً؛ نتحقق من دولة الخروج فعلياً…")
        }

        candidates.forEachIndexed { index, candidate ->
            val safeCandidate =
                "attempt=${index + 1}/${candidates.size}; protocol=${candidate.protocol.name}; source=${candidate.sourceId}; evidence=${candidate.countryEvidence}; preflight=${candidate.preflightLatencyMs ?: -1}ms"
            VpnDiagnostics.record(appContext, "attempt.start", safeCandidate)
            onProgress(
                "اختبار ${index + 1}/${candidates.size} · ${candidate.protocol.name} · " +
                    "${candidate.preflightLatencyMs?.let { "${it}ms" } ?: "متاح"}"
            )

            val attempt = runCatching {
                withTimeout(ATTEMPT_TIMEOUT_MS) {
                    check(candidate.countryEvidence == CountryEvidence.ENDPOINT_GEO_VERIFIED) {
                        "تم رفض مسار غير موثّق قبل تشغيل محرك VPN"
                    }
                    val config = SingBoxConfigBuilder.build(candidate)
                    VpnDiagnostics.record(appContext, "attempt.config", "${candidate.protocol.name}/${candidate.sourceId}")
                    val configFile = writeRuntimeConfig(country, config)
                    SingBoxVpnService.start(appContext, configFile.absolutePath)
                    active = true
                    VpnDiagnostics.record(appContext, "attempt.tun_started", "${candidate.protocol.name}/${candidate.sourceId}")
                    delay(TUNNEL_SETTLE_MS)

                    VpnDiagnostics.record(appContext, "attempt.verify_exit", "expected=${country.code}")
                    val quality = qualityClient.verify(country)
                    AutomaticConnectionResult(
                        quality = quality,
                        mode = ConnectionEngine.SING_BOX,
                        protocol = candidate.protocol.name,
                        sourceId = candidate.sourceId,
                        nodeName = candidate.displayName,
                        preflightLatencyMs = candidate.preflightLatencyMs,
                    )
                }
            }

            attempt.onSuccess { result ->
                VpnDiagnostics.record(
                    appContext,
                    "connection.verified",
                    "country=${country.code}; protocol=${candidate.protocol.name}; source=${candidate.sourceId}",
                )
                onProgress("تم التحقق من أن عنوان الخروج من ${country.displayNameAr} بنجاح.")
                return@withContext result
            }.onFailure { error ->
                val reason = error.userMessage()
                failures += "${candidate.protocol.name}/${candidate.sourceId}: $reason".take(220)
                VpnDiagnostics.record(
                    appContext,
                    "attempt.failed",
                    "${candidate.protocol.name}/${candidate.sourceId}; ${error.javaClass.simpleName}; $reason",
                )
                SingBoxVpnService.stopAndWait(appContext)
                active = false
                delay(BETWEEN_ATTEMPTS_MS)
            }
        }

        val recent = failures.takeLast(3)
        VpnDiagnostics.record(
            appContext,
            "connection.exhausted",
            "discovered=${discovered.size}; verified=${candidates.size}; attempted=${failures.size}",
        )
        throw IllegalStateException(
            buildString {
                if (candidates.isEmpty()) {
                    append("لا يوجد حالياً خادم مجاني حي وموثّق في ${country.displayNameAr}. لم نشغّل أي عقدة غير مؤكدة حتى لا يحدث اتصال خاطئ أو كراش.")
                } else {
                    append("اختبرنا ${candidates.size} خوادم موثقة جغرافياً في ${country.displayNameAr}، لكن لم يثبت أي منها عنوان خروج صالحاً بعد تشغيل النفق.")
                }
                if (recent.isNotEmpty()) {
                    append(" آخر النتائج: ")
                    append(recent.joinToString(" | "))
                }
                append("\n\nتقرير التشخيص:\n")
                append(VpnDiagnostics.summary(appContext, 10))
            }
        )
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        disconnectInternal()
    }

    private suspend fun disconnectInternal() {
        if (active || SingBoxVpnService.isRunning(appContext)) {
            SingBoxVpnService.stopAndWait(appContext)
        }
        active = false
    }

    private fun writeRuntimeConfig(country: VpnCountry, content: String): File {
        require(content.toByteArray(StandardCharsets.UTF_8).size <= MAX_CONFIG_BYTES) {
            "Generated VPN configuration is unexpectedly large"
        }
        val target = File(runtimeDir, "${country.tunnelName}-auto.json")
        val temp = File(runtimeDir, ".${country.tunnelName}-auto.tmp")
        temp.writeText(content, StandardCharsets.UTF_8)
        if (!temp.renameTo(target)) {
            target.writeText(content, StandardCharsets.UTF_8)
            temp.delete()
        }
        return target
    }

    private fun Throwable.userMessage(): String = message
        ?.replace('\n', ' ')
        ?.takeIf(String::isNotBlank)
        ?.take(260)
        ?: javaClass.simpleName.ifBlank { "فشل غير معروف" }

    companion object {
        private const val TUNNEL_SETTLE_MS = 1_600L
        private const val BETWEEN_ATTEMPTS_MS = 250L
        private const val ATTEMPT_TIMEOUT_MS = 25_000L
        private const val MAX_CONFIG_BYTES = 512 * 1024
    }
}

data class AutomaticConnectionResult(
    val quality: ConnectionQualityReport,
    val mode: ConnectionEngine,
    val protocol: String,
    val sourceId: String,
    val nodeName: String,
    val preflightLatencyMs: Long?,
)

enum class ConnectionEngine {
    SING_BOX,
}
