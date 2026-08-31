package com.vibe.app.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.VpnService
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.arabvpn.app.MainActivity
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
    private var platform: SingBoxPlatformInterface? = null
    private var initializationError: Throwable? = null
    private var commandServer: CommandServer? = null
    private var activeConfigPath: String? = null
    private var mockLocation: MockLocationController? = null

    override fun onCreate() {
        super.onCreate()

        // Native VPN initialization can fail on a specific ROM/ABI/device. Treat that as a
        // connection failure, never as a process-fatal startup crash.
        runCatching { createNotificationChannel() }
        runCatching { MockLocationController(applicationContext) }
            .onSuccess { mockLocation = it }
        runCatching {
            ensureLibboxSetup(applicationContext)
            SingBoxPlatformInterface(this)
        }.onSuccess {
            platform = it
            initializationError = null
        }.onFailure {
            initializationError = it
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val foregroundError = runCatching {
                    startForeground(NOTIFICATION_ID, buildNotification("جاري إنشاء الاتصال الآمن…"))
                }.exceptionOrNull()
                if (foregroundError != null) {
                    completeStart(Result.failure(foregroundError))
                    stopSelf(startId)
                    return START_NOT_STICKY
                }

                val initError = initializationError
                if (initError != null || platform == null) {
                    completeStart(
                        Result.failure(
                            IllegalStateException(
                                "تعذر تشغيل محرك VPN على هذا الجهاز",
                                initError,
                            )
                        )
                    )
                    runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                    stopSelf(startId)
                    return START_NOT_STICKY
                }

                val path = intent.getStringExtra(EXTRA_CONFIG_PATH)
                if (path.isNullOrBlank()) {
                    completeStart(Result.failure(IllegalArgumentException("Missing sing-box config path")))
                    runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                serviceScope.launch {
                    operationMutex.withLock {
                        runCatching { startRuntime(path) }
                            .onSuccess {
                                running = true
                                updateForegroundNotification("VPN متصل")
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

            ACTION_SYNC_LOCATION -> {
                val target = intent.readLocationTarget()
                if (target == null) {
                    completeLocationSync(LocationSyncResult.Failed("بيانات الموقع غير مكتملة"))
                    return START_NOT_STICKY
                }
                serviceScope.launch {
                    val result = mockLocation?.start(target)
                        ?: LocationSyncResult.Failed("خدمة الموقع غير متاحة على هذا الجهاز")
                    if (result is LocationSyncResult.Active && running) {
                        updateForegroundNotification("VPN والموقع متصلان · ${result.location.city}")
                    }
                    completeLocationSync(result)
                }
            }

            ACTION_BACKGROUND -> {
                if (running) {
                    updateForegroundNotification("VPN والموقع يعملان في الخلفية")
                }
            }

            ACTION_STOP -> {
                serviceScope.launch {
                    operationMutex.withLock {
                        runCatching { mockLocation?.stop() }
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
                runCatching { mockLocation?.stop() }
                stopRuntime()
                stopSelf()
            }
        }
        super.onRevoke()
    }

    override fun onDestroy() {
        runCatching { mockLocation?.stopNow() }
        runCatching { stopRuntime() }
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startRuntime(configPath: String) {
        // Keep the foreground state active while swapping the native runtime. Removing the
        // foreground notification here could let Android kill the VPN immediately after connect.
        stopRuntime(removeForeground = false)
        val activePlatform = checkNotNull(platform) { "sing-box platform is not initialized" }
        val file = File(configPath)
        require(file.isFile && file.length() in 1..MAX_CONFIG_BYTES) {
            "sing-box configuration is unavailable or too large"
        }
        val content = file.readText()
        require(content.isNotBlank()) { "sing-box configuration is empty" }

        val server = CommandServer(this, activePlatform)
        runCatching {
            server.start()
            server.startOrReloadService(content, OverrideOptions())
        }.onFailure {
            runCatching { server.closeService() }
            runCatching { server.close() }
            runCatching { activePlatform.closeTun() }
            throw it
        }
        commandServer = server
        activeConfigPath = configPath
    }

    private fun stopRuntime(removeForeground: Boolean = true) {
        running = false
        val server = commandServer
        commandServer = null
        activeConfigPath = null
        if (server != null) {
            runCatching { server.closeService() }
            runCatching { server.close() }
        }
        runCatching { platform?.closeTun() }
        if (removeForeground) {
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        }
    }

    override fun serviceStop() {
        serviceScope.launch {
            operationMutex.withLock {
                runCatching { mockLocation?.stop() }
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

    private fun updateForegroundNotification(text: String) {
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification(text))
        }
    }

    private fun buildNotification(text: String): android.app.Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.ic_arab_vpn)
            .setContentTitle("Arab VPN")
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun Intent.readLocationTarget(): CountryLocation? {
        if (!hasExtra(EXTRA_LATITUDE) || !hasExtra(EXTRA_LONGITUDE)) return null
        return CountryLocation(
            city = getStringExtra(EXTRA_CITY).orEmpty().ifBlank { "الموقع المختار" },
            latitude = getDoubleExtra(EXTRA_LATITUDE, Double.NaN),
            longitude = getDoubleExtra(EXTRA_LONGITUDE, Double.NaN),
            altitudeMeters = getDoubleExtra(EXTRA_ALTITUDE, 25.0),
            accuracyMeters = getFloatExtra(EXTRA_ACCURACY, 12f),
        ).takeIf {
            it.latitude.isFinite() && it.longitude.isFinite() &&
                it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0
        }
    }

    companion object {
        private const val ACTION_START = "com.malik05255.arabvpn.vpn.START_SING_BOX"
        private const val ACTION_STOP = "com.malik05255.arabvpn.vpn.STOP_SING_BOX"
        private const val ACTION_SYNC_LOCATION = "com.malik05255.arabvpn.vpn.SYNC_LOCATION"
        private const val ACTION_BACKGROUND = "com.malik05255.arabvpn.vpn.BACKGROUND_MODE"
        private const val EXTRA_CONFIG_PATH = "config_path"
        private const val EXTRA_CITY = "location_city"
        private const val EXTRA_LATITUDE = "location_latitude"
        private const val EXTRA_LONGITUDE = "location_longitude"
        private const val EXTRA_ALTITUDE = "location_altitude"
        private const val EXTRA_ACCURACY = "location_accuracy"
        private const val NOTIFICATION_CHANNEL = "arab_vpn_connection"
        private const val NOTIFICATION_ID = 7021
        private const val MAX_CONFIG_BYTES = 512L * 1024L

        @Volatile
        private var running = false

        @Volatile
        private var pendingStart: CompletableDeferred<Result<Unit>>? = null

        @Volatile
        private var pendingLocationSync: CompletableDeferred<LocationSyncResult>? = null

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
            try {
                ContextCompat.startForegroundService(context, intent)
                withTimeout(12_000) { deferred.await() }.getOrThrow()
            } finally {
                if (pendingStart === deferred) pendingStart = null
            }
        }

        suspend fun syncLocation(context: Context, target: CountryLocation): LocationSyncResult {
            if (!running) return LocationSyncResult.Failed("اتصال VPN غير نشط")
            val deferred = CompletableDeferred<LocationSyncResult>()
            pendingLocationSync?.cancel()
            pendingLocationSync = deferred
            val intent = Intent(context, SingBoxVpnService::class.java).apply {
                action = ACTION_SYNC_LOCATION
                putExtra(EXTRA_CITY, target.city)
                putExtra(EXTRA_LATITUDE, target.latitude)
                putExtra(EXTRA_LONGITUDE, target.longitude)
                putExtra(EXTRA_ALTITUDE, target.altitudeMeters)
                putExtra(EXTRA_ACCURACY, target.accuracyMeters)
            }
            return try {
                context.startService(intent)
                withTimeout(6_000) { deferred.await() }
            } catch (error: Throwable) {
                LocationSyncResult.Failed(error.message ?: "تعذر تشغيل الموقع في الخلفية")
            } finally {
                if (pendingLocationSync === deferred) pendingLocationSync = null
            }
        }

        fun enterBackgroundMode(context: Context) {
            if (!running) return
            runCatching {
                context.startService(
                    Intent(context, SingBoxVpnService::class.java).apply { action = ACTION_BACKGROUND }
                )
            }
        }

        fun stop(context: Context) {
            running = false
            runCatching {
                context.startService(
                    Intent(context, SingBoxVpnService::class.java).apply { action = ACTION_STOP }
                )
            }
        }

        fun isRunning(): Boolean = running

        private fun completeStart(result: Result<Unit>) {
            pendingStart?.complete(result)
            pendingStart = null
        }

        private fun completeLocationSync(result: LocationSyncResult) {
            pendingLocationSync?.complete(result)
            pendingLocationSync = null
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
