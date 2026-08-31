package com.arabvpn.app.update

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.vibe.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class UpdateCheckWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        runCatching {
            val manifest = GitHubUpdateClient().fetchLatestManifest() ?: return@runCatching
            if (manifest.versionCode > BuildConfig.VERSION_CODE) {
                UpdateNotifications.showAvailable(applicationContext, manifest)
            }
        }.fold(
            onSuccess = { Result.success() },
            onFailure = { Result.retry() },
        )
    }
}
