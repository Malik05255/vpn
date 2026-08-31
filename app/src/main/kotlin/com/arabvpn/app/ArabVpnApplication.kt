package com.arabvpn.app

import android.app.Application
import android.os.Handler
import android.os.Looper
import com.arabvpn.app.update.UpdateScheduler

/** Standalone application entry point for Arab VPN. */
class ArabVpnApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // The native VPN runtime uses its own :vpn process. WorkManager/update initialization must
        // stay in the main UI process so a tunnel restart cannot spawn duplicate schedulers.
        if (Application.getProcessName() != packageName) return

        Handler(Looper.getMainLooper()).postDelayed(
            {
                runCatching { UpdateScheduler.initialize(this) }
            },
            UPDATE_INIT_DELAY_MS,
        )
    }

    private companion object {
        const val UPDATE_INIT_DELAY_MS = 1_500L
    }
}
