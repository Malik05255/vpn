package com.vibe.app.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.wireguard.android.backend.Tunnel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.StandardCharsets

class AutomaticVpnManager(context: Context) {
    private val appContext = context.applicationContext
    private val catalog = FreeVpnCatalog()
    private val qualityClient = ConnectionQualityClient()
    private val profileStore = VpnProfileStore(appContext)
    private val wireGuard = WireGuardManager(appContext)
    private val runtimeDir = File(appContext.filesDir, "vpn-runtime").apply { mkdirs() }

    @Volatile
    private var activeMode: ActiveMode = ActiveMode.NONE

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
            onProgress("تم العثور على ${candidates.size} مسارات مرشحة؛ جاري اختبار الأسرع والأصح…")
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
                activeMode = ActiveMode.SING_BOX
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
                onProgress("تم اختيار أسرع مسار اجتاز فحص الدولة والسرعة والجودة.")
                return@withContext result
            }.onFailure { error ->
                failures += error.message.orEmpty().take(160)
                SingBoxVpnService.stop(appContext)
                activeMode = ActiveMode.NONE
                delay(BETWEEN_ATTEMPTS_MS)
            }
        }

        // A manually imported WireGuard profile is only a fallback. Automatic zero-cost discovery
        // remains the default and no credentials are bundled in the APK or repository.
        if (profileStore.hasProfile(country)) {
            onProgress("لم ينجح المسار العام؛ جاري فحص WireGuard الاحتياطي المحفوظ على جهازك…")
            val manual = runCatching {
                val state = wireGuard.connect(country, profileStore.load(country))
                check(state == Tunnel.State.UP) { "تعذر تشغيل WireGuard الاحتياطي" }
                activeMode = ActiveMode.WIRE_GUARD
                delay(TUNNEL_SETTLE_MS)
                val quality = qualityClient.verify(country)
                AutomaticConnectionResult(
                    quality = quality,
                    mode = ConnectionEngine.WIRE_GUARD,
                    protocol = "WireGuard",
                    sourceId = "manual-private-profile",
                    nodeName = "WireGuard احتياطي",
                    preflightLatencyMs = null,
                )
            }
            manual.onSuccess { return@withContext it }
            manual.onFailure { error ->
                failures += error.message.orEmpty().take(160)
                runCatching { wireGuard.disconnect() }
                activeMode = ActiveMode.NONE
            }
        }

        val detail = failures.filter(String::isNotBlank).lastOrNull()
        throw IllegalStateException(
            buildString {
                append("لا يوجد حالياً خادم مجاني حي في ${country.displayNameAr} يحقق معايير الدولة والسرعة والجودة المطلوبة.")
                if (!detail.isNullOrBlank()) append(" آخر فحص: $detail")
            }
        )
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        disconnectInternal()
    }

    private suspend fun disconnectInternal() {
        when (activeMode) {
            ActiveMode.SING_BOX -> SingBoxVpnService.stop(appContext)
            ActiveMode.WIRE_GUARD -> runCatching { wireGuard.disconnect() }
            ActiveMode.NONE -> {
                if (SingBoxVpnService.isRunning()) SingBoxVpnService.stop(appContext)
                runCatching { wireGuard.disconnect() }
            }
        }
        activeMode = ActiveMode.NONE
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

    private enum class ActiveMode {
        NONE,
        SING_BOX,
        WIRE_GUARD,
    }

    companion object {
        private const val TUNNEL_SETTLE_MS = 900L
        private const val BETWEEN_ATTEMPTS_MS = 250L
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
    WIRE_GUARD,
}
