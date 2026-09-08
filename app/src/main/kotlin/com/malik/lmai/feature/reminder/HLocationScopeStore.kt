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
        preferences.edit()
            .putBoolean("${prefix}_enabled", value.enabled)
            .putLong("${prefix}_lat", value.anchorLatitude?.toRawBits() ?: MISSING_DOUBLE_BITS)
            .putLong("${prefix}_lng", value.anchorLongitude?.toRawBits() ?: MISSING_DOUBLE_BITS)
            .putString("${prefix}_label", value.anchorLabel)
            .putLong("${prefix}_radius", value.radiusKm.toRawBits())
            .putLong("${prefix}_updated", value.updatedAtMs)
            .apply()
        mutableScope.value = value
    }

    private fun load(): HLocationScope {
        val prefix = prefix()
        val latBits = preferences.getLong("${prefix}_lat", MISSING_DOUBLE_BITS)
        val lngBits = preferences.getLong("${prefix}_lng", MISSING_DOUBLE_BITS)
        val radiusBits = preferences.getLong("${prefix}_radius", HLocationScope.DEFAULT_RADIUS_KM.toRawBits())
        return HLocationScope(
            enabled = preferences.getBoolean("${prefix}_enabled", false),
            anchorLatitude = latBits.takeUnless { it == MISSING_DOUBLE_BITS }?.let(Double::fromBits),
            anchorLongitude = lngBits.takeUnless { it == MISSING_DOUBLE_BITS }?.let(Double::fromBits),
            anchorLabel = preferences.getString("${prefix}_label", null),
            radiusKm = HLocationScopePolicy.normalizedRadiusKm(Double.fromBits(radiusBits)),
            updatedAtMs = preferences.getLong("${prefix}_updated", 0L),
        )
    }

    private fun prefix(): String = ownerIdentity.currentOwnerKey()
        .replace(Regex("[^A-Za-z0-9_-]"), "_")
        .take(120)

    companion object {
        private const val PREFS_NAME = "h_location_search_scope_v1"
        private const val MISSING_DOUBLE_BITS = Long.MIN_VALUE
    }
}
