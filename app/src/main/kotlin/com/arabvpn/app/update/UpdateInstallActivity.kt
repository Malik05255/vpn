package com.arabvpn.app.update

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * Opens Android's official package installer. Standard Android still requires the user's final
 * install confirmation; Arab VPN never attempts to bypass that security boundary.
 */
class UpdateInstallActivity : Activity() {
    private var requestedUnknownSourcesPermission = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        continueInstall()
    }

    override fun onResume() {
        super.onResume()
        if (requestedUnknownSourcesPermission && packageManager.canRequestPackageInstalls()) {
            requestedUnknownSourcesPermission = false
            continueInstall()
        }
    }

    private fun continueInstall() {
        val path = intent.getStringExtra(EXTRA_APK_PATH)
        if (path.isNullOrBlank()) {
            finish()
            return
        }
        val apk = File(path)
        if (!apk.isFile || !apk.canonicalPath.startsWith(File(cacheDir, "updates").canonicalPath)) {
            finish()
            return
        }

        if (!packageManager.canRequestPackageInstalls()) {
            requestedUnknownSourcesPermission = true
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName"),
                )
            )
            return
        }

        val uri = FileProvider.getUriForFile(
            this,
            "$packageName.files",
            apk,
        )
        startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        )
        finish()
    }

    companion object {
        const val EXTRA_APK_PATH = "apk_path"
    }
}
