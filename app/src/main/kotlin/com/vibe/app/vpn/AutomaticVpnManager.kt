package com.vibe.app.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Fully automatic connection manager.
 *
 * The user only chooses a country. No profile import, key entry or protocol configuration is
 * exposed: public candidates are discovered, preflighted, connected and then quality-verified.
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

        onProgress("جاري البحث عن خوادم مجانية متاحة في ${country.displayNameAr}…")
        val candidates = catalog.discover(country)
        val failures = mutableListOf<String>()

        if (candidates.isNotEmpty()) {
            onProgress("تم العثور على ${candidates.size} مسارات حية مرشحة؛ جاري اختبار الأفضل فعلياً…")
        }

        candidates.forEachIndexed { index, candidate ->
            onProgress(
                "اختبار المسار ${index + 1}/${candidates.size} · ${candidate.protocol.name} · " +
                    "${candidate.preflightLatencyMs?.let { "${it}ms" } ?: "متاح"}"
            )

            val attempt = runCatching {
                val config = SingBoxConfigBuilder.build(candidate)
                val configFile = writeRuntimeConfig(country, config)
                SingBoxVpnService.start(appContext, configFile.absolutePath)
                active = true

                // The VPN service can be running before Android has fully switched the process route.
                // Give the TUN a short initial settle period; ConnectionQualityClient then performs
                // additional real-network warmup retries before it rejects the candidate.
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

            attempt.onSuccess { result ->
                onProgress("تم اختيار مسار اجتاز فحص الدولة والسرعة والجودة.")
                return@withContext result
            }.onFailure { error ->
                failures += error.message.orEmpty().take(160)
                SingBoxVpnService.stop(appContext)
                active = false
                delay(BETWEEN_ATTEMPTS_MS)
            }
        }

        val detail = failures.filter(String::isNotBlank).lastOrNull()
        throw IllegalStateException(
            buildString {
                if (candidates.isEmpty()) {
                    append("لم يتم العثور حالياً على أي مسار مجاني حي في ${country.displayNameAr} من المصادر المتاحة.")
                } else {
                    append("تم اختبار ${candidates.size} مسارات حية في ${country.displayNameAr}، لكن لم يجتز أي منها التحقق الكامل من الدولة والجودة.")
                }
                if (!detail.isNullOrBlank()) append(" آخر فحص: $detail")
            }
        )
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        disconnectInternal()
    }

    private suspend fun disconnectInternal() {
        if (active || SingBoxVpnService.isRunning()) {
            SingBoxVpnService.stop(appContext)
        }
        active = false
        delay(150)
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

    companion object {
        private const val TUNNEL_SETTLE_MS = 1_400L
        private const val BETWEEN_ATTEMPTS_MS = 350L
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
