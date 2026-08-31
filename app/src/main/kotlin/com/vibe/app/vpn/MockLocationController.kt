package com.vibe.app.vpn

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Uses Android's official developer mock-location facility.
 * Android intentionally marks injected locations as mock; this class does not hide that marker.
 */
class MockLocationController(context: Context) {
    private val appContext = context.applicationContext
    private val locationManager = appContext.getSystemService(LocationManager::class.java)
    private val appOps = appContext.getSystemService(AppOpsManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var refreshJob: Job? = null

    fun isAuthorized(): Boolean = runCatching {
        appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_MOCK_LOCATION,
            Process.myUid(),
            appContext.packageName,
        ) == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    fun developerOptionsIntent(): Intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    suspend fun start(country: VpnCountry, ipLocation: IpLocation): LocationSyncResult =
        start(CountryLocationResolver.resolve(country, ipLocation))

    suspend fun start(target: CountryLocation): LocationSyncResult {
        // Always cancel an older refresh loop first. If authorization was revoked while a session
        // was active, the stale loop must not remain alive in the background.
        stop()

        if (!isAuthorized()) {
            return LocationSyncResult.NeedsDeveloperSetup
        }

        return runCatching {
            installProvider(LocationManager.GPS_PROVIDER)
            installProvider(LocationManager.NETWORK_PROVIDER)
            push(target)

            refreshJob = scope.launch {
                while (isActive) {
                    delay(REFRESH_INTERVAL_MS)
                    runCatching { push(target) }
                }
            }
            LocationSyncResult.Active(target)
        }.getOrElse { error ->
            LocationSyncResult.Failed(error.message ?: "تعذر تشغيل مزامنة الموقع")
        }
    }

    suspend fun stop() {
        refreshJob?.cancelAndJoin()
        refreshJob = null
        clearProviders()
    }

    /** Used by a foreground service during teardown where suspension is not available. */
    fun stopNow() {
        refreshJob?.cancel()
        refreshJob = null
        clearProviders()
    }

    private fun clearProviders() {
        if (!isAuthorized()) return
        runCatching { locationManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, false) }
        runCatching { locationManager.removeTestProvider(LocationManager.GPS_PROVIDER) }
        runCatching { locationManager.setTestProviderEnabled(LocationManager.NETWORK_PROVIDER, false) }
        runCatching { locationManager.removeTestProvider(LocationManager.NETWORK_PROVIDER) }
    }

    @Suppress("DEPRECATION")
    private fun installProvider(provider: String) {
        runCatching { locationManager.removeTestProvider(provider) }
        locationManager.addTestProvider(
            provider,
            false,
            provider == LocationManager.GPS_PROVIDER,
            provider == LocationManager.NETWORK_PROVIDER,
            false,
            true,
            true,
            true,
            ProviderProperties.POWER_USAGE_LOW,
            ProviderProperties.ACCURACY_FINE,
        )
        locationManager.setTestProviderEnabled(provider, true)
    }

    private fun push(target: CountryLocation) {
        val now = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtimeNanos()
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach { provider ->
            locationManager.setTestProviderLocation(
                provider,
                Location(provider).apply {
                    latitude = target.latitude
                    longitude = target.longitude
                    altitude = target.altitudeMeters
                    accuracy = target.accuracyMeters
                    time = now
                    elapsedRealtimeNanos = elapsed
                    speed = 0f
                    bearing = 0f
                },
            )
        }
    }

    companion object {
        private const val REFRESH_INTERVAL_MS = 2_000L
    }
}

sealed interface LocationSyncResult {
    data class Active(val location: CountryLocation) : LocationSyncResult
    data object NeedsDeveloperSetup : LocationSyncResult
    data class Failed(val reason: String) : LocationSyncResult
}

data class CountryLocation(
    val city: String,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double = 25.0,
    val accuracyMeters: Float = 12f,
)

object CountryLocationResolver {
    fun resolve(country: VpnCountry, ipLocation: IpLocation): CountryLocation {
        val latitude = ipLocation.latitude
        val longitude = ipLocation.longitude
        if (
            latitude != null && longitude != null &&
            latitude in -90.0..90.0 && longitude in -180.0..180.0 &&
            ipLocation.countryCode.equals(country.code, ignoreCase = true)
        ) {
            return CountryLocation(
                city = ipLocation.city.ifBlank { country.displayNameAr },
                latitude = latitude,
                longitude = longitude,
            )
        }

        return when (country) {
            VpnCountry.EGYPT -> CountryLocation("القاهرة", 30.0444, 31.2357, 23.0)
            VpnCountry.JORDAN -> CountryLocation("عمّان", 31.9539, 35.9106, 757.0)
            VpnCountry.MOROCCO -> CountryLocation("الدار البيضاء", 33.5731, -7.5898, 27.0)
        }
    }
}
