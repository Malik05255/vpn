package com.arabvpn.app.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

class UpdateDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_DOWNLOAD_UPDATE) return
        enqueue(context)
    }

    companion object {
        const val ACTION_DOWNLOAD_UPDATE = "com.malik05255.arabvpn.DOWNLOAD_UPDATE"
        private const val DOWNLOAD_WORK = "arab-vpn-download-update"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<UpdateDownloadWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                DOWNLOAD_WORK,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }
}
