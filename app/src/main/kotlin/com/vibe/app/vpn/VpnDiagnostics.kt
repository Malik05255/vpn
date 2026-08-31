package com.vibe.app.vpn

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Small privacy-safe diagnostic journal for real-device failures.
 * Never write proxy credentials, share links, config JSON, phone identifiers, or location history.
 */
object VpnDiagnostics {
    private const val FILE_NAME = "vpn-diagnostics.log"
    private const val MAX_FILE_CHARS = 16_000
    private const val MAX_DETAIL_CHARS = 280
    private val lock = Any()

    fun reset(context: Context, country: VpnCountry) {
        synchronized(lock) {
            runCatching {
                file(context).writeText(
                    "ArabVPN diagnostic v2 | country=${country.code} | started=${timestamp()}\n"
                )
            }
        }
    }

    fun record(context: Context, stage: String, detail: String = "") {
        val safeStage = stage
            .replace(Regex("[^A-Za-z0-9_.-]"), "_")
            .take(48)
        val safeDetail = sanitize(detail)
        synchronized(lock) {
            runCatching {
                val target = file(context)
                val line = "${timestamp()} | $safeStage${if (safeDetail.isBlank()) "" else " | $safeDetail"}\n"
                val previous = if (target.isFile) target.readText() else ""
                val combined = (previous + line).takeLast(MAX_FILE_CHARS)
                target.writeText(combined)
            }
        }
    }

    fun summary(context: Context, maxLines: Int = 12): String = synchronized(lock) {
        runCatching {
            val target = file(context)
            if (!target.isFile) return@runCatching "لا يوجد تقرير تشخيص بعد."
            target.readLines()
                .filter(String::isNotBlank)
                .takeLast(maxLines.coerceIn(1, 30))
                .joinToString("\n")
                .take(3_000)
        }.getOrElse { "تعذر قراءة تقرير التشخيص." }
    }

    private fun sanitize(value: String): String = value
        .replace(Regex("(?i)(vless|vmess|trojan|ss|hysteria2|hy2|tuic)://\\S+"), "[share-link-hidden]")
        .replace(Regex("(?i)(password|uuid|token|secret|public_key|short_id)\\s*[=:]\\s*\\S+"), "$1=[hidden]")
        .replace('\n', ' ')
        .replace('\r', ' ')
        .trim()
        .take(MAX_DETAIL_CHARS)

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    private fun timestamp(): String = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
}
