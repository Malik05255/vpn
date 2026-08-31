package com.arabvpn.app

import android.app.Application
import com.arabvpn.app.update.UpdateScheduler

/** Standalone application entry point for Arab VPN. */
class ArabVpnApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        UpdateScheduler.initialize(this)
    }
}
