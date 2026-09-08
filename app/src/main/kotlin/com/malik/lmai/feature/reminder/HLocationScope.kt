package com.malik.lmai.feature.reminder

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

enum class HTravelExpiryMode { UNTIL_RETURN, HOURS_24, DAYS_3, MANUAL }

enum class HLocationScopeSource { BASE, TRAVEL }

data class HLocationScope(
    val enabled: Boolean = false,
    val anchorLatitude: Double? = null,
    val anchorLongitude: Double? = null,
    val anchorLabel: String? = null,
    val radiusKm: Double = DEFAULT_RADIUS_KM,
    val travelEnabled: Boolean = false,
    val travelLatitude: Double? = null,
    val travelLongitude: Double? = null,
    val travelLabel: String? = null,
    val travelRadiusKm: Double = DEFAULT_RADIUS_KM,
    val travelExpiryMode: HTravelExpiryMode = HTravelExpiryMode.HOURS_24,
    val travelExpiresAtMs: Long? = null,
    val updatedAtMs: Long = 0L,
) {
    val isConfigured: Boolean
        get() = enabled && anchorLatitude != null && anchorLongitude != null

    val isTravelConfigured: Boolean
        get() = travelEnabled && travelLatitude != null && travelLongitude != null

    companion object {
        const val DEFAULT_RADIUS_KM = 100.0
        const val MIN_RADIUS_KM = 5.0
        const val MAX_RADIUS_KM = 250.0
        val PRESET_RADII_KM = listOf(25.0, 50.0, 100.0, 200.0)
    }
}

data class HLocationScopeEvaluation(
    val configured: Boolean,
    val inside: Boolean,
    val distanceKm: Double? = null,
    val source: HLocationScopeSource = HLocationScopeSource.BASE,
)

data class HEffectiveLocationScope(
    val configured: Boolean,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val label: String? = null,
    val radiusKm: Double = HLocationScope.DEFAULT_RADIUS_KM,
    val source: HLocationScopeSource = HLocationScopeSource.BASE,
)

object HLocationScopePolicy {
    private const val EARTH_RADIUS_KM = 6371.0088

    fun evaluate(scope: HLocationScope, latitude: Double, longitude: Double): HLocationScopeEvaluation =
        evaluateAgainst(
            configured = scope.isConfigured,
            anchorLat = scope.anchorLatitude,
            anchorLng = scope.anchorLongitude,
            radiusKm = scope.radiusKm,
            latitude = latitude,
            longitude = longitude,
            source = HLocationScopeSource.BASE,
        )

    fun effectiveScope(
        scope: HLocationScope,
        currentLatitude: Double? = null,
        currentLongitude: Double? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): HEffectiveLocationScope {
        val returnedToBase = scope.travelExpiryMode == HTravelExpiryMode.UNTIL_RETURN &&
            currentLatitude != null && currentLongitude != null &&
            evaluate(scope, currentLatitude, currentLongitude).inside
        val expired = scope.travelExpiresAtMs?.let { nowMs >= it } == true
        val travelActive = scope.isTravelConfigured && !expired && !returnedToBase
        return if (travelActive) {
            HEffectiveLocationScope(
                configured = true,
                latitude = scope.travelLatitude,
                longitude = scope.travelLongitude,
                label = scope.travelLabel,
                radiusKm = scope.travelRadiusKm,
                source = HLocationScopeSource.TRAVEL,
            )
        } else {
            HEffectiveLocationScope(
                configured = scope.isConfigured,
                latitude = scope.anchorLatitude,
                longitude = scope.anchorLongitude,
                label = scope.anchorLabel,
                radiusKm = scope.radiusKm,
                source = HLocationScopeSource.BASE,
            )
        }
    }

    fun evaluateEffective(
        scope: HLocationScope,
        latitude: Double,
        longitude: Double,
        currentLatitude: Double? = null,
        currentLongitude: Double? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): HLocationScopeEvaluation {
        val effective = effectiveScope(scope, currentLatitude, currentLongitude, nowMs)
        return evaluateAgainst(
            configured = effective.configured,
            anchorLat = effective.latitude,
            anchorLng = effective.longitude,
            radiusKm = effective.radiusKm,
            latitude = latitude,
            longitude = longitude,
            source = effective.source,
        )
    }

    fun normalizedRadiusKm(value: Double): Double =
        value.coerceIn(HLocationScope.MIN_RADIUS_KM, HLocationScope.MAX_RADIUS_KM)

    fun distanceKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
        return 2 * EARTH_RADIUS_KM * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    private fun evaluateAgainst(
        configured: Boolean,
        anchorLat: Double?,
        anchorLng: Double?,
        radiusKm: Double,
        latitude: Double,
        longitude: Double,
        source: HLocationScopeSource,
    ): HLocationScopeEvaluation {
        if (!configured || anchorLat == null || anchorLng == null) {
            return HLocationScopeEvaluation(configured = false, inside = false, source = source)
        }
        val distance = distanceKm(anchorLat, anchorLng, latitude, longitude)
        return HLocationScopeEvaluation(
            configured = true,
            inside = distance <= radiusKm,
            distanceKm = distance,
            source = source,
        )
    }
}

class HLocationScopeException(message: String) : IllegalStateException(message)
