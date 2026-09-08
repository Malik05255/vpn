package com.malik.lmai.feature.reminder

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Search scope for H place resolution.
 *
 * This is intentionally NOT a geofence. The configured radius only limits which places H may
 * resolve for ambiguous/local requests. The reminder itself keeps its small POI geofence.
 */
data class HGeoPoint(
    val latitude: Double,
    val longitude: Double,
    val label: String? = null,
) {
    init {
        require(latitude in -90.0..90.0) { "latitude out of range" }
        require(longitude in -180.0..180.0) { "longitude out of range" }
    }
}

enum class HLocationAnchorSource {
    NONE,
    BASE,
    TRAVEL,
}

data class HLocationScopeConfig(
    val baseAnchor: HGeoPoint? = null,
    val radiusKm: Double = DEFAULT_RADIUS_KM,
    val travelAnchor: HGeoPoint? = null,
    val travelExpiresAtMs: Long? = null,
    val explicitDistantOverride: Boolean = true,
) {
    init {
        require(radiusKm in MIN_RADIUS_KM..MAX_RADIUS_KM) { "radiusKm out of range" }
    }

    fun activeAnchor(nowMs: Long = System.currentTimeMillis()): Pair<HLocationAnchorSource, HGeoPoint>? {
        val travel = travelAnchor
        if (travel != null && (travelExpiresAtMs == null || travelExpiresAtMs > nowMs)) {
            return HLocationAnchorSource.TRAVEL to travel
        }
        return baseAnchor?.let { HLocationAnchorSource.BASE to it }
    }

    val travelModeEnabled: Boolean get() = travelAnchor != null

    companion object {
        const val DEFAULT_RADIUS_KM = 100.0
        const val MIN_RADIUS_KM = 5.0
        const val MAX_RADIUS_KM = 500.0
        val RADIUS_PRESETS_KM = listOf(25.0, 50.0, 100.0, 200.0)
    }
}

enum class HLocationScopeOutcome {
    /** No anchor has been configured; preserve the pre-scope behavior. */
    NO_ANCHOR,

    /** Candidate is inside the active base/travel search radius. */
    IN_SCOPE,

    /** Candidate is outside the radius and the request did not explicitly name a distant place. */
    OUTSIDE_SCOPE,

    /** Explicit city/place wins over the default radius, but is marked so H can explain it. */
    EXPLICIT_OVERRIDE,

    /** Device is currently outside the base scope and travel mode is not active. */
    OUTSIDE_BASE_SCOPE,
}

data class HLocationScopeDecision(
    val outcome: HLocationScopeOutcome,
    val allowed: Boolean,
    val anchorSource: HLocationAnchorSource,
    val distanceKm: Double? = null,
    val radiusKm: Double? = null,
) {
    val needsScopeAdjustment: Boolean get() = outcome == HLocationScopeOutcome.OUTSIDE_BASE_SCOPE
}

object HLocationScopePolicy {
    fun evaluateCandidate(
        config: HLocationScopeConfig,
        candidate: HGeoPoint,
        explicitDistantPlace: Boolean = false,
        nowMs: Long = System.currentTimeMillis(),
    ): HLocationScopeDecision {
        val active = config.activeAnchor(nowMs)
            ?: return HLocationScopeDecision(
                outcome = HLocationScopeOutcome.NO_ANCHOR,
                allowed = true,
                anchorSource = HLocationAnchorSource.NONE,
            )

        val (source, anchor) = active
        val distance = distanceKm(anchor, candidate)
        if (distance <= config.radiusKm) {
            return HLocationScopeDecision(
                outcome = HLocationScopeOutcome.IN_SCOPE,
                allowed = true,
                anchorSource = source,
                distanceKm = distance,
                radiusKm = config.radiusKm,
            )
        }

        if (explicitDistantPlace && config.explicitDistantOverride) {
            return HLocationScopeDecision(
                outcome = HLocationScopeOutcome.EXPLICIT_OVERRIDE,
                allowed = true,
                anchorSource = source,
                distanceKm = distance,
                radiusKm = config.radiusKm,
            )
        }

        return HLocationScopeDecision(
            outcome = HLocationScopeOutcome.OUTSIDE_SCOPE,
            allowed = false,
            anchorSource = source,
            distanceKm = distance,
            radiusKm = config.radiusKm,
        )
    }

    /**
     * Determines whether an unqualified local request may use the configured base scope.
     * Travel mode intentionally bypasses the base-distance warning and uses its temporary anchor.
     */
    fun evaluateCurrentPosition(
        config: HLocationScopeConfig,
        current: HGeoPoint,
        nowMs: Long = System.currentTimeMillis(),
    ): HLocationScopeDecision {
        val active = config.activeAnchor(nowMs)
            ?: return HLocationScopeDecision(
                outcome = HLocationScopeOutcome.NO_ANCHOR,
                allowed = true,
                anchorSource = HLocationAnchorSource.NONE,
            )

        val (source, anchor) = active
        val distance = distanceKm(anchor, current)
        if (source == HLocationAnchorSource.TRAVEL || distance <= config.radiusKm) {
            return HLocationScopeDecision(
                outcome = HLocationScopeOutcome.IN_SCOPE,
                allowed = true,
                anchorSource = source,
                distanceKm = distance,
                radiusKm = config.radiusKm,
            )
        }

        return HLocationScopeDecision(
            outcome = HLocationScopeOutcome.OUTSIDE_BASE_SCOPE,
            allowed = false,
            anchorSource = HLocationAnchorSource.BASE,
            distanceKm = distance,
            radiusKm = config.radiusKm,
        )
    }

    fun distanceKm(a: HGeoPoint, b: HGeoPoint): Double {
        val earthRadiusKm = 6371.0088
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * earthRadiusKm * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }
}
