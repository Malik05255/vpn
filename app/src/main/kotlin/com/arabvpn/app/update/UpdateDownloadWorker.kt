package com.arabvpn.app.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.tencent.tinker.bsdiff.BSPatch
import com.vibe.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

class UpdateDownloadWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        setForeground(UpdateNotifications.downloadingForeground(applicationContext, -1))

        runCatching {
            val client = GitHubUpdateClient()
            val manifest = client.fetchLatestManifest()
                ?: error("لا يوجد إصدار Arab VPN منشور حالياً")
            if (manifest.versionCode <= BuildConfig.VERSION_CODE) return@runCatching

            val updateDir = File(applicationContext.cacheDir, "updates").apply {
                deleteRecursively()
                mkdirs()
            }
            val candidate = File(updateDir, "ArabVPN-${manifest.versionCode}.apk")
            val plan = manifest.bestAssetFor(BuildConfig.VERSION_CODE)

            val usedDelta = if (plan is DownloadPlan.Delta) {
                runCatching {
                    val patch = File(updateDir, "update.bsdiff")
                    client.download(plan.asset, patch)
                    requireSha256(patch, plan.asset.sha256)
                    applyDelta(patch, candidate)
                    requireSha256(candidate, manifest.full.sha256)
                    validateApk(candidate, manifest.versionCode)
                    true
                }.getOrElse {
                    candidate.delete()
                    false
                }
            } else {
                false
            }

            if (!usedDelta) {
                client.download(manifest.full, candidate)
                requireSha256(candidate, manifest.full.sha256)
                validateApk(candidate, manifest.versionCode)
            }

            UpdateNotifications.showReady(applicationContext, candidate, manifest, usedDelta)
        }.fold(
            onSuccess = { Result.success() },
            onFailure = { error ->
                UpdateNotifications.showFailure(
                    applicationContext,
                    error.message ?: "تعذر تجهيز التحديث",
                )
                Result.failure()
            },
        )
    }

    private fun applyDelta(patch: File, outputApk: File) {
        val installedApk = File(applicationContext.applicationInfo.sourceDir)
        require(installedApk.isFile) { "تعذر قراءة نسخة التطبيق المثبتة" }

        FileInputStream(installedApk).use { oldInput ->
            FileInputStream(patch).use { patchInput ->
                val result = BSPatch.patchFast(oldInput, patchInput, outputApk)
                require(result == BSPatch.RETURN_SUCCESS) {
                    "فشل تركيب فرق التحديث ($result)"
                }
            }
        }
    }

    private fun requireSha256(file: File, expected: String) {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        require(actual.equals(expected, ignoreCase = true)) {
            "فشل التحقق من سلامة ملف التحديث"
        }
    }

    @Suppress("DEPRECATION")
    private fun validateApk(apk: File, expectedVersionCode: Int) {
        val packageManager = applicationContext.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val candidate = packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: error("ملف التحديث ليس APK صالحاً")
        val installed = packageManager.getPackageInfo(applicationContext.packageName, flags)

        require(candidate.packageName == applicationContext.packageName) {
            "حزمة التحديث لا تخص Arab VPN"
        }
        require(candidate.longVersionCode == expectedVersionCode.toLong()) {
            "رقم إصدار التحديث غير متطابق"
        }
        require(signers(candidate) == signers(installed)) {
            "توقيع التحديث لا يطابق توقيع التطبيق المثبت"
        }
    }

    private fun signers(info: PackageInfo): Set<String> {
        val signingInfo = info.signingInfo ?: return emptySet()
        val certificates = if (signingInfo.hasMultipleSigners()) {
            signingInfo.apkContentsSigners
        } else {
            signingInfo.signingCertificateHistory
        }
        val digest = MessageDigest.getInstance("SHA-256")
        return certificates.mapTo(mutableSetOf()) { certificate ->
            digest.reset()
            digest.digest(certificate.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }
}
