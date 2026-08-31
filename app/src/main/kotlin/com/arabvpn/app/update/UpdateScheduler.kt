package com.arabvpn.app.update

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object UpdateScheduler {
    private const val PERIODIC_WORK = "arab-vpn-periodic-update-check"
    private const val STARTUP_WORK = "arab-vpn-startup-update-check"

    fun initialize(context: Context) {
        UpdateNotifications.createChannel(context)
        val workManager = WorkManager.getInstance(context)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        // UPDATE is deliberate: older installs used KEEP with a 12-hour interval, which meant
        // that schedule could survive app upgrades forever. Move every existing install to the
        // new responsive schedule while still keeping one unique periodic worker.
        val periodic = PeriodicWorkRequestBuilder<UpdateCheckWorker>(30, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            periodic,
        )

        // Force a fresh check whenever the app process starts. REPLACE avoids an old queued
        // startup check blocking the current version from checking immediately.
        val startup = OneTimeWorkRequestBuilder<UpdateCheckWorker>()
            .setConstraints(constraints)
            .build()
        workManager.enqueueUniqueWork(
            STARTUP_WORK,
            ExistingWorkPolicy.REPLACE,
            startup,
        )
    }
}
