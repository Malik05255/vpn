package com.arabvpn.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.io.File

/**
 * Keeps the user in one continuous update flow: permission -> download -> verification -> the
 * official Android package installer. Android still requires the user's final install confirmation.
 */
class UpdateInstallActivity : ComponentActivity() {
    private var requestedUnknownSourcesPermission = false
    private var downloadStarted = false
    private var pendingApkPath: String? = null

    private lateinit var statusView: TextView
    private lateinit var progressView: ProgressBar
    private lateinit var actionButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildContentView()

        pendingApkPath = intent.getStringExtra(EXTRA_APK_PATH)
        when {
            pendingApkPath != null -> ensureInstallPermission()
            intent.action == ACTION_DOWNLOAD_AND_INSTALL -> ensureInstallPermission()
            else -> finish()
        }
    }

    override fun onResume() {
        super.onResume()
        if (requestedUnknownSourcesPermission) {
            requestedUnknownSourcesPermission = false
            if (packageManager.canRequestPackageInstalls()) {
                proceedAfterPermission()
            } else {
                showPermissionRequired()
            }
        }
    }

    private fun buildContentView() {
        val padding = (24 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(padding, padding, padding, padding)
        }

        progressView = ProgressBar(this).apply {
            isIndeterminate = true
            visibility = View.GONE
        }
        statusView = TextView(this).apply {
            text = "جاري تجهيز تحديث Arab VPN…"
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(0, padding, 0, padding)
        }
        actionButton = Button(this).apply {
            visibility = View.GONE
        }

        container.addView(progressView)
        container.addView(statusView)
        container.addView(actionButton)
        setContentView(container)
    }

    private fun ensureInstallPermission() {
        if (packageManager.canRequestPackageInstalls()) {
            proceedAfterPermission()
            return
        }

        requestedUnknownSourcesPermission = true
        statusView.text = "اسمح لـ Arab VPN بتثبيت التحديثات من هذا المصدر مرة واحدة."
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName"),
                )
            )
        }.onFailure {
            showPermissionRequired()
        }
    }

    private fun showPermissionRequired() {
        progressView.visibility = View.GONE
        statusView.text = "يلزم تفعيل «السماح من هذا المصدر» حتى يفتح Android شاشة تثبيت التحديث."
        actionButton.apply {
            text = "فتح الإعدادات"
            visibility = View.VISIBLE
            setOnClickListener { ensureInstallPermission() }
        }
    }

    private fun proceedAfterPermission() {
        actionButton.visibility = View.GONE
        val readyPath = pendingApkPath
        if (!readyPath.isNullOrBlank()) {
            openInstaller(readyPath)
        } else {
            startDownloadAndInstall()
        }
    }

    private fun startDownloadAndInstall() {
        if (downloadStarted) return
        downloadStarted = true
        progressView.visibility = View.VISIBLE
        statusView.text = "جاري تنزيل التحديث والتحقق منه…"

        val workId = UpdateDownloadReceiver.enqueue(this)
        WorkManager.getInstance(this)
            .getWorkInfoByIdLiveData(workId)
            .observe(this) { info ->
                if (info == null) return@observe
                when (info.state) {
                    WorkInfo.State.SUCCEEDED -> {
                        if (info.outputData.getBoolean(UpdateDownloadWorker.KEY_ALREADY_CURRENT, false)) {
                            progressView.visibility = View.GONE
                            statusView.text = "أنت تستخدم أحدث إصدار بالفعل."
                            actionButton.apply {
                                text = "إغلاق"
                                visibility = View.VISIBLE
                                setOnClickListener { finish() }
                            }
                            return@observe
                        }

                        val apkPath = info.outputData.getString(UpdateDownloadWorker.KEY_APK_PATH)
                        if (apkPath.isNullOrBlank()) {
                            showFailure("اكتمل التنزيل لكن لم يتم العثور على ملف التحديث.")
                        } else {
                            pendingApkPath = apkPath
                            statusView.text = "تم التحقق من التحديث. جارٍ فتح مثبت Android…"
                            openInstaller(apkPath)
                        }
                    }

                    WorkInfo.State.FAILED -> {
                        val message = info.outputData.getString(UpdateDownloadWorker.KEY_ERROR_MESSAGE)
                            ?: "تعذر تجهيز التحديث"
                        showFailure(message)
                    }

                    WorkInfo.State.CANCELLED -> showFailure("تم إلغاء تنزيل التحديث")
                    else -> Unit
                }
            }
    }

    private fun showFailure(message: String) {
        progressView.visibility = View.GONE
        val signatureMismatch = message.contains("توقيع", ignoreCase = true)
        statusView.text = if (signatureMismatch) {
            "نسخة Arab VPN المثبتة تحمل توقيع اختبار قديم، لذلك يمنع Android تحديثها فوق النسخة الجديدة. " +
                "يلزم حذف نسخة Arab VPN القديمة وتثبيت النسخة الجديدة مرة واحدة فقط؛ بعد ذلك ستعمل التحديثات فوق بعضها بشكل طبيعي."
        } else {
            "تعذر تثبيت التحديث: $message"
        }
        actionButton.apply {
            text = if (signatureMismatch) "إغلاق" else "إعادة المحاولة"
            visibility = View.VISIBLE
            setOnClickListener {
                if (signatureMismatch) {
                    finish()
                } else {
                    downloadStarted = false
                    startDownloadAndInstall()
                }
            }
        }
    }

    private fun openInstaller(path: String) {
        val apk = File(path)
        val updateRoot = File(cacheDir, "updates").canonicalFile
        val canonicalApk = runCatching { apk.canonicalFile }.getOrNull()
        if (
            canonicalApk == null ||
            !canonicalApk.isFile ||
            !canonicalApk.path.startsWith(updateRoot.path + File.separator)
        ) {
            showFailure("ملف التحديث غير صالح")
            return
        }

        val uri = FileProvider.getUriForFile(
            this,
            "$packageName.files",
            canonicalApk,
        )
        runCatching {
            startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            )
            finish()
        }.onFailure {
            showFailure(it.message ?: "تعذر فتح مثبت Android")
        }
    }

    companion object {
        const val EXTRA_APK_PATH = "apk_path"
        const val ACTION_DOWNLOAD_AND_INSTALL = "com.malik05255.arabvpn.DOWNLOAD_AND_INSTALL"

        fun downloadAndInstallIntent(context: Context): Intent =
            Intent(context, UpdateInstallActivity::class.java).apply {
                action = ACTION_DOWNLOAD_AND_INSTALL
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
    }
}
