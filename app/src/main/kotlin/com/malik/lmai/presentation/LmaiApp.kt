package com.malik.lmai.presentation

import android.app.Application
import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.WorkManager
import com.malik.lmai.data.preferences.AppText
import com.malik.lmai.data.preferences.LanguageManager
import com.malik.lmai.feature.agent.service.AgentNotificationHelper
import com.malik.lmai.feature.ai.FreeAiBootstrapper
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class LmaiApp : Application() {
    // TODO Delete when https://github.com/google/dagger/issues/3601 is resolved.
    @Inject
    @ApplicationContext
    lateinit var context: Context

    @Inject
    lateinit var languageManager: LanguageManager

    @Inject
    lateinit var notificationHelper: AgentNotificationHelper

    @Inject
    lateinit var freeAiBootstrapper: FreeAiBootstrapper

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        AppText.initialize(this)

        runCatching {
            languageManager.applyStoredLanguage()
        }

        runCatching {
            notificationHelper.createChannels()
        }

        // H is cloud-only. Cancel every legacy local-model download and remove model
        // artifacts left on devices that upgrade from an older build.
        purgeLegacyLocalAiArtifacts()

        appScope.launch {
            runCatching {
                freeAiBootstrapper.ensureReady()
            }
        }

        runCatching {
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    runCatching {
                        notificationHelper.cancelAllResultNotifications()
                    }
                }
            })
        }
    }

    private fun purgeLegacyLocalAiArtifacts() {
        runCatching {
            val workManager = WorkManager.getInstance(this)
            LEGACY_LOCAL_WORK_NAMES.forEach(workManager::cancelUniqueWork)
        }

        runCatching {
            File(noBackupFilesDir, LEGACY_LOCAL_MODEL_DIRECTORY).deleteRecursively()
        }
    }

    companion object {
        private const val LEGACY_LOCAL_MODEL_DIRECTORY = "h_models"
        private val LEGACY_LOCAL_WORK_NAMES = listOf(
            "h-local-model-download-v1",
            "h-local-model-download-v2-connected",
            "h-local-model-download-v3-unmetered",
        )
    }
}
