package com.malik.lmai.feature.reminder

import android.content.Context
import com.malik.lmai.feature.assistant.HOwnerIdentity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Singleton
class HLocationScopeStore @Inject constructor(
    @ApplicationContext context: Context,
    private val ownerIdentity: HOwnerIdentity,
) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val mutableScope = MutableStateFlow(load())
    val scope: StateFlow<HLocationScope> = mutableScope.asStateFlow()

    fun current(): HLocationScope {
        val value = load()
        if (value != mutableScope.value) mutableScope.value = value
        return value
    }

    fun pin(latitude: Double, longitude: Double, label: String?, radiusKm: Double = current().radiusKm): HLocationScope {
        val old = current()
        val value = old.copy(
            enabled = true,
            anchorLatitude = latitude,
            anchorLongitude = longitude,
            anchorLabel = label?.trim()?.takeIf { it.isNotBlank() },
            radiusKm = HLocationScopePolicy.normalizedRadiusKm(radiusKm),
            updatedAtMs = System.currentTimeMillis(),
        )
        save(value)
        return value
    }

    fun setRadiusKm(radiusKm: Double): HLocationScope {
        val old = current()
        val value = old.copy(
            radiusKm = HLocationScopePolicy.normalizedRadiusKm(radiusKm),
            updatedAtMs = System.currentTimeMillis(),
        )
        save(value)
        return value
    }

    fun startTravel(
        latitude: Double,
        longitude: Double,
        label: String?,
        mode: HTravelExpiryMode,
        radiusKm: Double = HLocationScope.DEFAULT_RADIUS_KM,
        nowMs: Long = System.currentTimeMillis(),
    ): HLocationScope {
        val expiresAt = when (mode) {
            HTravelExpiryMode.HOURS_24 -> nowMs + 24L * 60L * 60L * 1000L
            HTravelExpiryMode.DAYS_3 -> nowMs + 3L * 24L * 60L * 60L * 1000L
            HTravelExpiryMode.UNTIL_RETURN, HTravelExpiryMode.MANUAL -> null
        }
        val value = current().copy(
            travelEnabled = true,
            travelLatitude = latitude,
            travelLongitude = longitude,
            travelLabel = label?.trim()?.takeIf { it.isNotBlank() },
            travelRadiusKm = HLocationScopePolicy.normalizedRadiusKm(radiusKm),
            travelExpiryMode = mode,
            travelExpiresAtMs = expiresAt,
            updatedAtMs = nowMs,
        )
        save(value)
        return value
    }

    fun stopTravel(): HLocationScope {
        val value = current().copy(
            travelEnabled = false,
            travelLatitude = null,
            travelLongitude = null,
            travelLabel = null,
            travelExpiresAtMs = null,
            updatedAtMs = System.currentTimeMillis(),
        )
        save(value)
        return value
    }

    fun clear(): HLocationScope {
        val old = current()
        val value = HLocationScope(radiusKm = old.radiusKm, travelRadiusKm = old.travelRadiusKm)
        save(value)
        return value
    }

    fun refresh() {
        mutableScope.value = load()
    }

    private fun save(value: HLocationScope) {
        val prefix = prefix()
        val editor = preferences.edit()
            .putBoolean("${prefix}_enabled", value.enabled)
            .putString("${prefix}_label", value.anchorLabel)
            .putString("${prefix}_radius", value.radiusKm.toString())
            .putBoolean("${prefix}_travel_enabled", value.travelEnabled)
            .putString("${prefix}_travel_label", value.travelLabel)
            .putString("${prefix}_travel_radius", value.travelRadiusKm.toString())
            .putString("${prefix}_travel_expiry_mode", value.travelExpiryMode.name)
            .putLong("${prefix}_updated", value.updatedAtMs)
        putDoubleOrRemove(editor, "${prefix}_lat", value.anchorLatitude)
        putDoubleOrRemove(editor, "${prefix}_lng", value.anchorLongitude)
        putDoubleOrRemove(editor, "${prefix}_travel_lat", value.travelLatitude)
        putDoubleOrRemove(editor, "${prefix}_travel_lng", value.travelLongitude)
        if (value.travelExpiresAtMs != null) editor.putLong("${prefix}_travel_expires", value.travelExpiresAtMs)
        else editor.remove("${prefix}_travel_expires")
        editor.apply()
        mutableScope.value = value
    }

    private fun load(): HLocationScope {
        val prefix = prefix()
        val radius = preferences.getString("${prefix}_radius", null)?.toDoubleOrNull()
            ?: HLocationScope.DEFAULT_RADIUS_KM
        val travelRadius = preferences.getString("${prefix}_travel_radius", null)?.toDoubleOrNull()
            ?: HLocationScope.DEFAULT_RADIUS_KM
        val travelMode = runCatching {
            enumValueOf<HTravelExpiryMode>(preferences.getString("${prefix}_travel_expiry_mode", null).orEmpty())
        }.getOrDefault(HTravelExpiryMode.HOURS_24)
        return HLocationScope(
            enabled = preferences.getBoolean("${prefix}_enabled", false),
            anchorLatitude = preferences.getString("${prefix}_lat", null)?.toDoubleOrNull(),
            anchorLongitude = preferences.getString("${prefix}_lng", null)?.toDoubleOrNull(),
            anchorLabel = preferences.getString("${prefix}_label", null),
            radiusKm = HLocationScopePolicy.normalizedRadiusKm(radius),
            travelEnabled = preferences.getBoolean("${prefix}_travel_enabled", false),
            travelLatitude = preferences.getString("${prefix}_travel_lat", null)?.toDoubleOrNull(),
            travelLongitude = preferences.getString("${prefix}_travel_lng", null)?.toDoubleOrNull(),
            travelLabel = preferences.getString("${prefix}_travel_label", null),
            travelRadiusKm = HLocationScopePolicy.normalizedRadiusKm(travelRadius),
            travelExpiryMode = travelMode,
            travelExpiresAtMs = if (preferences.contains("${prefix}_travel_expires")) preferences.getLong("${prefix}_travel_expires", 0L) else null,
            updatedAtMs = preferences.getLong("${prefix}_updated", 0L),
        )
    }

    private fun putDoubleOrRemove(editor: android.content.SharedPreferences.Editor, key: String, value: Double?) {
        if (value != null) editor.putString(key, value.toString()) else editor.remove(key)
    }

    private fun prefix(): String = ownerIdentity.currentOwnerKey()
        .replace(Regex("[^A-Za-z0-9_-]"), "_")
        .take(120)

    companion object {
        private const val PREFS_NAME = "h_location_search_scope_v1"
    }
}
