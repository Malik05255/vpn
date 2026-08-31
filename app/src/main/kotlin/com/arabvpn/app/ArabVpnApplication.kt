package com.arabvpn.app

import android.app.Application
import android.os.Handler
import android.os.Looper
import com.arabvpn.app.update.UpdateScheduler

/** Standalone application entry point for Arab VPN. */
class ArabVpnApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // Updates are useful, but they must never be able to prevent the VPN UI from starting.
        // Defer initialization until after the first frame and isolate any OEM/WorkManager issue.
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
