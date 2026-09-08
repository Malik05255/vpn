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
        val value = HLocationScope(
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

    fun clear(): HLocationScope {
        val value = HLocationScope(radiusKm = current().radiusKm)
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
            .putLong("${prefix}_updated", value.updatedAtMs)
        if (value.anchorLatitude != null) editor.putString("${prefix}_lat", value.anchorLatitude.toString())
        else editor.remove("${prefix}_lat")
        if (value.anchorLongitude != null) editor.putString("${prefix}_lng", value.anchorLongitude.toString())
        else editor.remove("${prefix}_lng")
        editor.apply()
        mutableScope.value = value
    }

    private fun load(): HLocationScope {
        val prefix = prefix()
        val radius = preferences.getString("${prefix}_radius", null)?.toDoubleOrNull()
            ?: HLocationScope.DEFAULT_RADIUS_KM
        return HLocationScope(
            enabled = preferences.getBoolean("${prefix}_enabled", false),
            anchorLatitude = preferences.getString("${prefix}_lat", null)?.toDoubleOrNull(),
            anchorLongitude = preferences.getString("${prefix}_lng", null)?.toDoubleOrNull(),
            anchorLabel = preferences.getString("${prefix}_label", null),
            radiusKm = HLocationScopePolicy.normalizedRadiusKm(radius),
            updatedAtMs = preferences.getLong("${prefix}_updated", 0L),
        )
    }

    private fun prefix(): String = ownerIdentity.currentOwnerKey()
        .replace(Regex("[^A-Za-z0-9_-]"), "_")
        .take(120)

    companion object {
        private const val PREFS_NAME = "h_location_search_scope_v1"
    }
}
