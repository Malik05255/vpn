package com.malik.lmai.feature.reminder

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class HLocationScope(
    val enabled: Boolean = false,
    val anchorLatitude: Double? = null,
    val anchorLongitude: Double? = null,
    val anchorLabel: String? = null,
    val radiusKm: Double = DEFAULT_RADIUS_KM,
    val updatedAtMs: Long = 0L,
) {
    val isConfigured: Boolean
        get() = enabled && anchorLatitude != null && anchorLongitude != null

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
)

object HLocationScopePolicy {
    private const val EARTH_RADIUS_KM = 6371.0088

    fun evaluate(scope: HLocationScope, latitude: Double, longitude: Double): HLocationScopeEvaluation {
        val anchorLat = scope.anchorLatitude
        val anchorLng = scope.anchorLongitude
        if (!scope.isConfigured || anchorLat == null || anchorLng == null) {
            return HLocationScopeEvaluation(configured = false, inside = false)
        }
        val distance = distanceKm(anchorLat, anchorLng, latitude, longitude)
        return HLocationScopeEvaluation(
            configured = true,
            inside = distance <= scope.radiusKm,
            distanceKm = distance,
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
}

class HLocationScopeException(message: String) : IllegalStateException(message)
