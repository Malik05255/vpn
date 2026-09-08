package com.malik.lmai.presentation.ui.reminder

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.malik.lmai.feature.reminder.HGeoPoint
import com.malik.lmai.feature.reminder.HLocationScopeConfig
import com.malik.lmai.feature.reminder.HLocationScopeStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@HiltViewModel
class HLocationScopeViewModel @Inject constructor(
    private val store: HLocationScopeStore,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val _config = MutableStateFlow(store.load())
    val config: StateFlow<HLocationScopeConfig> = _config.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun refresh() {
        _config.value = store.load()
    }

    fun setRadiusKm(radiusKm: Double) {
        store.setRadiusKm(radiusKm)
        refresh()
    }

    fun setBaseAnchor(point: HGeoPoint) {
        store.setBaseAnchor(point)
        refresh()
        _message.value = "تم تثبيت نقطة الارتكاز"
    }

    fun clearBaseAnchor() {
        store.setBaseAnchor(null)
        store.clearTravel()
        refresh()
        _message.value = "تم إلغاء نقطة الارتكاز"
    }

    fun useCurrentAsBase() = useCurrentLocation { point ->
        store.setBaseAnchor(point.copy(label = "موقعي الحالي"))
        refresh()
        _message.value = "تم تثبيت موقعك الحالي كنقطة ارتكاز"
    }

    fun startTravelFromCurrent(hours: Int = 24) = useCurrentLocation { point ->
        val expiresAt = System.currentTimeMillis() + hours.coerceIn(1, 168) * 60L * 60L * 1000L
        store.startTravelMode(point.copy(label = "نطاق السفر الحالي"), expiresAt)
        refresh()
        _message.value = "تم تشغيل وضع السفر لمدة $hours ساعة"
    }

    fun setTravelAnchor(point: HGeoPoint, hours: Int = 24) {
        val expiresAt = System.currentTimeMillis() + hours.coerceIn(1, 168) * 60L * 60L * 1000L
        store.startTravelMode(point, expiresAt)
        refresh()
        _message.value = "تم تحديث نطاق السفر"
    }

    fun stopTravel() {
        store.clearTravel()
        refresh()
        _message.value = "تم إيقاف وضع السفر"
    }

    fun setExplicitOverride(enabled: Boolean) {
        store.setExplicitDistantOverride(enabled)
        refresh()
    }

    fun clearMessage() {
        _message.value = null
    }

    private fun useCurrentLocation(onReady: (HGeoPoint) -> Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            _message.value = "فعّل إذن الموقع أولًا ثم حاول مرة أخرى"
            return
        }

        val client = LocationServices.getFusedLocationProviderClient(context)
        client.lastLocation
            .addOnSuccessListener { last ->
                if (last != null) {
                    onReady(HGeoPoint(last.latitude, last.longitude))
                } else {
                    client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
                        .addOnSuccessListener { current ->
                            if (current != null) onReady(HGeoPoint(current.latitude, current.longitude))
                            else _message.value = "تعذر تحديد موقعك الحالي. تأكد من تشغيل الموقع"
                        }
                        .addOnFailureListener { error ->
                            _message.value = "تعذر تحديد الموقع: ${error.message ?: "خطأ غير معروف"}"
                        }
                }
            }
            .addOnFailureListener { error ->
                _message.value = "تعذر تحديد الموقع: ${error.message ?: "خطأ غير معروف"}"
            }
    }
}
