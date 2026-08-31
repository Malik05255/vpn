package com.vibe.app.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.VpnService
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.vibe.app.R
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.SetupOptions
import io.nekohasekai.libbox.SystemProxyStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.Locale

class SingBoxVpnService : VpnService(), CommandServerHandler {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val operationMutex = Mutex()
    private lateinit var platform: SingBoxPlatformInterface
    private var commandServer: CommandServer? = null
    private var activeConfigPath: String? = null

    override fun onCreate() {
        super.onCreate()
        ensureLibboxSetup(applicationContext)
        platform = SingBoxPlatformInterface(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, buildNotification("جاري إنشاء الاتصال الآمن…"))
                val path = intent.getStringExtra(EXTRA_CONFIG_PATH)
                if (path.isNullOrBlank()) {
                    completeStart(Result.failure(IllegalArgumentException("Missing sing-box config path")))
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                serviceScope.launch {
                    operationMutex.withLock {
                        runCatching { startRuntime(path) }
                            .onSuccess {
                                running = true
                                getSystemService(NotificationManager::class.java)
                                    .notify(NOTIFICATION_ID, buildNotification("VPN متصل"))
                                completeStart(Result.success(Unit))
                            }
                            .onFailure { error ->
                                stopRuntime()
                                completeStart(Result.failure(error))
                                stopSelf(startId)
                            }
                    }
                }
            }

            ACTION_STOP -> {
                serviceScope.launch {
                    operationMutex.withLock {
                        stopRuntime()
                        stopSelf(startId)
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        running = false
        serviceScope.launch {
            operationMutex.withLock {
                stopRuntime()
                stopSelf()
            }
        }
        super.onRevoke()
    }

    override fun onDestroy() {
        runCatching { stopRuntime() }
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startRuntime(configPath: String) {
        stopRuntime()
        val file = File(configPath)
        require(file.isFile && file.length() in 1..MAX_CONFIG_BYTES) {
            "sing-box configuration is unavailable or too large"
        }
        val content = file.readText()
        require(content.isNotBlank()) { "sing-box configuration is empty" }

        val server = CommandServer(this, platform)
        runCatching {
            server.start()
            server.startOrReloadService(content, OverrideOptions())
        }.onFailure {
            runCatching { server.closeService() }
            runCatching { server.close() }
            platform.closeTun()
            throw it
        }
        commandServer = server
        activeConfigPath = configPath
    }

    private fun stopRuntime() {
        running = false
        val server = commandServer
        commandServer = null
        activeConfigPath = null
        if (server != null) {
            runCatching { server.closeService() }
            runCatching { server.close() }
        }
        platform.closeTun()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
    }

    override fun serviceStop() {
        serviceScope.launch {
            operationMutex.withLock {
                stopRuntime()
                stopSelf()
            }
        }
    }

    override fun serviceReload() {
        val path = activeConfigPath ?: return
        val content = File(path).takeIf(File::isFile)?.readText().orEmpty()
        if (content.isNotBlank()) commandServer?.startOrReloadService(content, OverrideOptions())
    }

    override fun getSystemProxyStatus(): SystemProxyStatus = SystemProxyStatus().apply {
        available = false
        enabled = false
    }

    override fun setSystemProxyEnabled(isEnabled: Boolean) = Unit
    override fun connectSSHAgent(): Int = -1
    override fun triggerNativeCrash() = error("Native crash requested")
    override fun writeDebugMessage(message: String?) = Unit

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL,
                "Arab VPN",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "حالة اتصال VPN"
                setShowBadge(false)
            }
        )
    }

    private fun buildNotification(text: String) = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL)
        .setSmallIcon(R.drawable.ic_arab_vpn)
        .setContentTitle("Arab VPN")
        .setContentText(text)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .build()

    companion object {
        private const val ACTION_START = "com.malik05255.arabvpn.vpn.START_SING_BOX"
        private const val ACTION_STOP = "com.malik05255.arabvpn.vpn.STOP_SING_BOX"
        private const val EXTRA_CONFIG_PATH = "config_path"
        private const val NOTIFICATION_CHANNEL = "arab_vpn_connection"
        private const val NOTIFICATION_ID = 7021
        private const val MAX_CONFIG_BYTES = 512L * 1024L

        @Volatile
        private var running = false

        @Volatile
        private var pendingStart: CompletableDeferred<Result<Unit>>? = null

        @Volatile
        private var libboxInitialized = false

        suspend fun start(context: Context, configPath: String) {
            val deferred = CompletableDeferred<Result<Unit>>()
            pendingStart?.cancel()
            pendingStart = deferred
            val intent = Intent(context, SingBoxVpnService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_CONFIG_PATH, configPath)
            }
            ContextCompat.startForegroundService(context, intent)
            try {
                withTimeout(12_000) { deferred.await() }.getOrThrow()
            } finally {
                if (pendingStart === deferred) pendingStart = null
            }
        }

        fun stop(context: Context) {
            running = false
            context.startService(
                Intent(context, SingBoxVpnService::class.java).apply { action = ACTION_STOP }
            )
        }

        fun isRunning(): Boolean = running

        private fun completeStart(result: Result<Unit>) {
            pendingStart?.complete(result)
            pendingStart = null
        }

        @Synchronized
        private fun ensureLibboxSetup(context: Context) {
            if (libboxInitialized) return
            val baseDir = File(context.filesDir, "sing-box").apply { mkdirs() }
            val workingDir = File(baseDir, "runtime").apply { mkdirs() }
            val tempDir = File(context.cacheDir, "sing-box").apply { mkdirs() }
            val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

            Libbox.setLocale(Locale.getDefault().toLanguageTag())
            Libbox.setup(
                SetupOptions().apply {
                    basePath = baseDir.absolutePath
                    workingPath = workingDir.absolutePath
                    tempPath = tempDir.absolutePath
                    fixAndroidStack = true
                    logMaxLines = 1_000
                    debug = debuggable
                    crashReportSource = "ArabVPN"
                }
            )
            libboxInitialized = true
        }
    }
}
