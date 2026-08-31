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
 * torn down before the next one starts.
 */
class AutomaticVpnManager(context: Context) {
    private val appContext = context.applicationContext
    private val catalog = FreeVpnCatalog()
    private val qualityClient = ConnectionQualityClient()
    private val runtimeDir = File(appContext.filesDir, "vpn-runtime").apply { mkdirs() }

    @Volatile
    private var active = false

    fun preparePermissionIntent(): Intent? = VpnService.prepare(appContext)

    suspend fun connect(
        country: VpnCountry,
        onProgress: (String) -> Unit = {},
    ): AutomaticConnectionResult = withContext(Dispatchers.IO) {
        disconnectInternal()

        onProgress("جاري جمع واختبار المسارات المجانية في ${country.displayNameAr}…")
        val candidates = catalog.discover(country)
        val failures = mutableListOf<String>()

        if (candidates.isNotEmpty()) {
            onProgress("وجدنا ${candidates.size} مسارات اجتازت الفحص الأولي؛ نتحقق من دولة الخروج فعلياً…")
        }

        candidates.forEachIndexed { index, candidate ->
            onProgress(
                "اختبار ${index + 1}/${candidates.size} · ${candidate.protocol.name} · " +
                    "${candidate.preflightLatencyMs?.let { "${it}ms" } ?: "متاح"}"
            )

            val attempt = runCatching {
                withTimeout(ATTEMPT_TIMEOUT_MS) {
                    val config = SingBoxConfigBuilder.build(candidate)
                    val configFile = writeRuntimeConfig(country, config)
                    SingBoxVpnService.start(appContext, configFile.absolutePath)
                    active = true
                    delay(TUNNEL_SETTLE_MS)

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
                onProgress("تم التحقق من أن عنوان الخروج من ${country.displayNameAr} بنجاح.")
                return@withContext result
            }.onFailure { error ->
                failures += "${candidate.protocol.name}/${candidate.sourceId}: ${error.userMessage()}".take(220)
                SingBoxVpnService.stopAndWait(appContext)
                active = false
                delay(BETWEEN_ATTEMPTS_MS)
            }
        }

        val recent = failures.takeLast(3)
        throw IllegalStateException(
            buildString {
                if (candidates.isEmpty()) {
                    append("لم نجد مساراً حياً قابلاً للاستخدام في ${country.displayNameAr} من المصادر الحالية.")
                } else {
                    append("اختبرنا ${candidates.size} مسارات في ${country.displayNameAr} ولم يثبت أي مسار دولة الخروج المطلوبة حتى الآن.")
                }
                if (recent.isNotEmpty()) {
                    append(" آخر النتائج: ")
                    append(recent.joinToString(" | "))
                }
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
        ?: javaClass.simpleName.ifBlank { "فشل غير معروف" }

    companion object {
        private const val TUNNEL_SETTLE_MS = 1_200L
        private const val BETWEEN_ATTEMPTS_MS = 200L
        private const val ATTEMPT_TIMEOUT_MS = 22_000L
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
