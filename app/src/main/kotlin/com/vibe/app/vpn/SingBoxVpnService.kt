package com.vibe.app.vpn

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
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

/**
 * The native sing-box engine intentionally runs in the manifest-declared `:vpn` process.
 * A fatal Go/native crash therefore cannot terminate the main Arab VPN UI process.
 */
class SingBoxVpnService : VpnService(), CommandServerHandler {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val operationMutex = Mutex()
    private var platform: SingBoxPlatformInterface? = null
    private var initializationError: Throwable? = null
    private var commandServer: CommandServer? = null
    private var activeConfigPath: String? = null
    private var mockLocation: MockLocationController? = null
    private var running = false

    override fun onCreate() {
        super.onCreate()
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
        val receiver = intent?.resultReceiver()
        when (intent?.action) {
            ACTION_START -> handleStart(intent, receiver, startId)
            ACTION_SYNC_LOCATION -> handleLocationSync(intent, receiver)
            ACTION_BACKGROUND -> if (running) {
                updateForegroundNotification("VPN والموقع يعملان في الخلفية")
            }
            ACTION_STOP -> handleStop(receiver, startId)
        }
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent, receiver: ResultReceiver?, startId: Int) {
        writeServiceState(STATE_STARTING)
        val foregroundError = runCatching {
            startForeground(NOTIFICATION_ID, buildNotification("جاري إنشاء الاتصال الآمن…"))
        }.exceptionOrNull()
        if (foregroundError != null) {
            writeServiceState(STATE_STOPPED)
            receiver.sendFailure(foregroundError)
            stopSelf(startId)
            return
        }

        val initError = initializationError
        if (initError != null || platform == null) {
            writeServiceState(STATE_STOPPED)
            receiver.sendFailure(
                IllegalStateException("تعذر تشغيل محرك VPN على هذا الجهاز", initError)
            )
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            stopSelf(startId)
            return
        }

        val path = intent.getStringExtra(EXTRA_CONFIG_PATH)
        if (path.isNullOrBlank()) {
            writeServiceState(STATE_STOPPED)
            receiver.sendFailure(IllegalArgumentException("Missing sing-box config path"))
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            stopSelf(startId)
            return
        }

        serviceScope.launch {
            operationMutex.withLock {
                runCatching { startRuntime(path) }
                    .onSuccess {
                        running = true
                        writeServiceState(STATE_RUNNING)
                        updateForegroundNotification("VPN متصل")
                        receiver?.send(RESULT_START_OK, Bundle.EMPTY)
                    }
                    .onFailure { error ->
                        stopRuntime()
                        writeServiceState(STATE_STOPPED)
                        receiver.sendFailure(error)
                        stopSelf(startId)
                    }
            }
        }
    }

    private fun handleLocationSync(intent: Intent, receiver: ResultReceiver?) {
        if (!running) {
            receiver?.send(RESULT_LOCATION_FAILED, messageBundle("اتصال VPN غير نشط"))
            return
        }
        val target = intent.readLocationTarget()
        if (target == null) {
            receiver?.send(RESULT_LOCATION_FAILED, messageBundle("بيانات الموقع غير مكتملة"))
            return
        }
        serviceScope.launch {
            val result = mockLocation?.start(target)
                ?: LocationSyncResult.Failed("خدمة الموقع غير متاحة على هذا الجهاز")
            when (result) {
                is LocationSyncResult.Active -> {
                    if (running) updateForegroundNotification("VPN والموقع متصلان · ${result.location.city}")
                    receiver?.send(RESULT_LOCATION_ACTIVE, Bundle.EMPTY)
                }
                LocationSyncResult.NeedsDeveloperSetup ->
                    receiver?.send(RESULT_LOCATION_NEEDS_SETUP, Bundle.EMPTY)
                is LocationSyncResult.Failed ->
                    receiver?.send(RESULT_LOCATION_FAILED, messageBundle(result.reason))
            }
        }
    }

    private fun handleStop(receiver: ResultReceiver?, startId: Int) {
        serviceScope.launch {
            operationMutex.withLock {
                runCatching { mockLocation?.stop() }
                stopRuntime()
                writeServiceState(STATE_STOPPED)
                receiver?.send(RESULT_STOP_OK, Bundle.EMPTY)
                stopSelf(startId)
            }
        }
    }

    override fun onRevoke() {
        running = false
        writeServiceState(STATE_STOPPED)
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
        running = false
        writeServiceState(STATE_STOPPED)
        runCatching { mockLocation?.stopNow() }
        runCatching { stopRuntime() }
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startRuntime(configPath: String) {
        stopRuntime(removeForeground = false)
        val activePlatform = checkNotNull(platform) { "sing-box platform is not initialized" }
        val file = File(configPath)
        require(file.isFile && file.length() in 1..MAX_CONFIG_BYTES) {
            "sing-box configuration is unavailable or too large"
        }
        val content = file.readText()
        require(content.isNotBlank()) { "sing-box configuration is empty" }

        Libbox.checkConfig(content)

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
        if (removeForeground) runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
    }

    override fun serviceStop() {
        running = false
        writeServiceState(STATE_STOPPED)
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
        if (content.isNotBlank()) {
            runCatching {
                Libbox.checkConfig(content)
                commandServer?.startOrReloadService(content, OverrideOptions())
            }.onFailure {
                writeServiceState(STATE_STOPPED)
                serviceStop()
            }
        }
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

    @Suppress("DEPRECATION")
    private fun Intent.resultReceiver(): ResultReceiver? = getParcelableExtra(EXTRA_RESULT_RECEIVER)

    private fun writeServiceState(state: String) {
        runCatching {
            stateFile(applicationContext).apply {
                parentFile?.mkdirs()
                writeText(state)
            }
        }
    }

    companion object {
        private const val ACTION_START = "com.malik05255.arabvpn.vpn.START_SING_BOX"
        private const val ACTION_STOP = "com.malik05255.arabvpn.vpn.STOP_SING_BOX"
        private const val ACTION_SYNC_LOCATION = "com.malik05255.arabvpn.vpn.SYNC_LOCATION"
        private const val ACTION_BACKGROUND = "com.malik05255.arabvpn.vpn.BACKGROUND_MODE"
        private const val EXTRA_CONFIG_PATH = "config_path"
        private const val EXTRA_RESULT_RECEIVER = "result_receiver"
        private const val EXTRA_ERROR_MESSAGE = "error_message"
        private const val EXTRA_CITY = "location_city"
        private const val EXTRA_LATITUDE = "location_latitude"
        private const val EXTRA_LONGITUDE = "location_longitude"
        private const val EXTRA_ALTITUDE = "location_altitude"
        private const val EXTRA_ACCURACY = "location_accuracy"
        private const val NOTIFICATION_CHANNEL = "arab_vpn_connection"
        private const val NOTIFICATION_ID = 7021
        private const val MAX_CONFIG_BYTES = 512L * 1024L
        private const val IPC_TIMEOUT_MS = 12_000L
        private const val STOP_TIMEOUT_MS = 4_000L

        private const val RESULT_START_OK = 10
        private const val RESULT_FAILED = 11
        private const val RESULT_LOCATION_ACTIVE = 20
        private const val RESULT_LOCATION_NEEDS_SETUP = 21
        private const val RESULT_LOCATION_FAILED = 22
        private const val RESULT_STOP_OK = 30

        private const val STATE_STARTING = "starting"
        private const val STATE_RUNNING = "running"
        private const val STATE_STOPPED = "stopped"
        private const val STATE_FILE_NAME = "service.state"

        @Volatile
        private var libboxInitialized = false

        suspend fun start(context: Context, configPath: String) {
            val deferred = CompletableDeferred<Result<Unit>>()
            val receiver = resultReceiver { code, data ->
                when (code) {
                    RESULT_START_OK -> deferred.complete(Result.success(Unit))
                    RESULT_FAILED -> deferred.complete(
                        Result.failure(IllegalStateException(data?.getString(EXTRA_ERROR_MESSAGE) ?: "تعذر تشغيل محرك VPN"))
                    )
                }
            }
            val intent = Intent(context, SingBoxVpnService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_CONFIG_PATH, configPath)
                putExtra(EXTRA_RESULT_RECEIVER, receiver)
            }
            try {
                ContextCompat.startForegroundService(context, intent)
                withTimeout(IPC_TIMEOUT_MS) { deferred.await() }.getOrThrow()
            } catch (error: Throwable) {
                if (!isVpnProcessAlive(context)) {
                    markStopped(context)
                    throw IllegalStateException("توقف محرك VPN أثناء تشغيل المسار؛ تم عزله عن التطبيق وسيتم تجربة مسار آخر.", error)
                }
                throw error
            }
        }

        suspend fun syncLocation(context: Context, target: CountryLocation): LocationSyncResult {
            if (!isRunning(context)) return LocationSyncResult.Failed("اتصال VPN غير نشط")
            val deferred = CompletableDeferred<LocationSyncResult>()
            val receiver = resultReceiver { code, data ->
                val result = when (code) {
                    RESULT_LOCATION_ACTIVE -> LocationSyncResult.Active(target)
                    RESULT_LOCATION_NEEDS_SETUP -> LocationSyncResult.NeedsDeveloperSetup
                    RESULT_LOCATION_FAILED -> LocationSyncResult.Failed(
                        data?.getString(EXTRA_ERROR_MESSAGE) ?: "تعذر تشغيل الموقع في الخلفية"
                    )
                    else -> null
                }
                if (result != null) deferred.complete(result)
            }
            val intent = Intent(context, SingBoxVpnService::class.java).apply {
                action = ACTION_SYNC_LOCATION
                putExtra(EXTRA_RESULT_RECEIVER, receiver)
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
                if (!isVpnProcessAlive(context)) markStopped(context)
                LocationSyncResult.Failed(error.message ?: "تعذر تشغيل الموقع في الخلفية")
            }
        }

        suspend fun stopAndWait(context: Context) {
            if (!isVpnProcessAlive(context)) {
                markStopped(context)
                return
            }
            val deferred = CompletableDeferred<Unit>()
            val receiver = resultReceiver { code, _ ->
                if (code == RESULT_STOP_OK) deferred.complete(Unit)
            }
            runCatching {
                context.startService(
                    Intent(context, SingBoxVpnService::class.java).apply {
                        action = ACTION_STOP
                        putExtra(EXTRA_RESULT_RECEIVER, receiver)
                    }
                )
                withTimeout(STOP_TIMEOUT_MS) { deferred.await() }
            }
            markStopped(context)
        }

        fun enterBackgroundMode(context: Context) {
            if (!isRunning(context)) return
            runCatching {
                context.startService(
                    Intent(context, SingBoxVpnService::class.java).apply { action = ACTION_BACKGROUND }
                )
            }
        }

        fun isRunning(context: Context): Boolean {
            val state = runCatching { stateFile(context).takeIf(File::isFile)?.readText()?.trim() }.getOrNull()
            if (state != STATE_RUNNING) return false
            if (!isVpnProcessAlive(context)) {
                markStopped(context)
                return false
            }
            return true
        }

        private fun isVpnProcessAlive(context: Context): Boolean {
            val expectedName = "${context.packageName}:vpn"
            val activityManager = context.getSystemService(ActivityManager::class.java) ?: return false
            return activityManager.runningAppProcesses.orEmpty().any { it.processName == expectedName }
        }

        private fun stateFile(context: Context): File =
            File(File(context.filesDir, "vpn-runtime").apply { mkdirs() }, STATE_FILE_NAME)

        private fun markStopped(context: Context) {
            runCatching { stateFile(context).writeText(STATE_STOPPED) }
        }

        private fun resultReceiver(onResult: (Int, Bundle?) -> Unit): ResultReceiver =
            object : ResultReceiver(Handler(Looper.getMainLooper())) {
                override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                    onResult(resultCode, resultData)
                }
            }

        private fun ResultReceiver?.sendFailure(error: Throwable) {
            this?.send(
                RESULT_FAILED,
                messageBundle(error.message ?: error.javaClass.simpleName.ifBlank { "تعذر تشغيل محرك VPN" })
            )
        }

        private fun messageBundle(message: String): Bundle = Bundle().apply {
            putString(EXTRA_ERROR_MESSAGE, message.take(400))
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
