package com.malik.lmai.feature.reminder

import android.content.Context
import com.malik.lmai.feature.assistant.HOwnerIdentity
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** Lightweight per-owner persistence for H's place-search anchor and travel scope. */
@Singleton
class HLocationScopeStore @Inject constructor(
    @ApplicationContext context: Context,
    private val ownerIdentity: HOwnerIdentity,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(nowMs: Long = System.currentTimeMillis()): HLocationScopeConfig {
        val prefix = prefix()
        val radius = prefs.getString("${prefix}radius_km", null)?.toDoubleOrNull()
            ?.coerceIn(HLocationScopeConfig.MIN_RADIUS_KM, HLocationScopeConfig.MAX_RADIUS_KM)
            ?: HLocationScopeConfig.DEFAULT_RADIUS_KM
        val base = readPoint(prefix, "base")
        val travel = readPoint(prefix, "travel")
        val travelExpiry = prefs.getLong("${prefix}travel_expires_at", 0L).takeIf { it > 0L }
        val allowExplicit = prefs.getBoolean("${prefix}explicit_override", true)

        val config = HLocationScopeConfig(
            baseAnchor = base,
            radiusKm = radius,
            travelAnchor = travel,
            travelExpiresAtMs = travelExpiry,
            explicitDistantOverride = allowExplicit,
        )
        if (travel != null && travelExpiry != null && travelExpiry <= nowMs) {
            clearTravel()
            return config.copy(travelAnchor = null, travelExpiresAtMs = null)
        }
        return config
    }

    fun setBaseAnchor(point: HGeoPoint?) {
        writePoint("base", point)
    }

    fun setRadiusKm(radiusKm: Double) {
        val safe = radiusKm.coerceIn(HLocationScopeConfig.MIN_RADIUS_KM, HLocationScopeConfig.MAX_RADIUS_KM)
        prefs.edit().putString("${prefix()}radius_km", safe.toString()).apply()
    }

    fun setExplicitDistantOverride(enabled: Boolean) {
        prefs.edit().putBoolean("${prefix()}explicit_override", enabled).apply()
    }

    fun startTravelMode(point: HGeoPoint, expiresAtMs: Long? = null) {
        writePoint("travel", point)
        prefs.edit().apply {
            if (expiresAtMs != null) putLong("${prefix()}travel_expires_at", expiresAtMs)
            else remove("${prefix()}travel_expires_at")
        }.apply()
    }

    fun clearTravel() {
        val prefix = prefix()
        prefs.edit()
            .remove("${prefix}travel_lat")
            .remove("${prefix}travel_lon")
            .remove("${prefix}travel_label")
            .remove("${prefix}travel_expires_at")
            .apply()
    }

    fun evaluateCandidate(
        candidate: HGeoPoint,
        explicitDistantPlace: Boolean = false,
        nowMs: Long = System.currentTimeMillis(),
    ): HLocationScopeDecision = HLocationScopePolicy.evaluateCandidate(
        config = load(nowMs),
        candidate = candidate,
        explicitDistantPlace = explicitDistantPlace,
        nowMs = nowMs,
    )

    fun evaluateCurrentPosition(
        current: HGeoPoint,
        nowMs: Long = System.currentTimeMillis(),
    ): HLocationScopeDecision = HLocationScopePolicy.evaluateCurrentPosition(load(nowMs), current, nowMs)

    /**
     * Keep settings isolated per H owner without exposing account identifiers in preference keys.
     * SHA-256 is used only as a deterministic storage namespace, not as authentication.
     */
    private fun prefix(): String {
        val ownerKey = ownerIdentity.currentOwnerKey()
        val digest = MessageDigest.getInstance("SHA-256").digest(ownerKey.toByteArray(Charsets.UTF_8))
        val namespace = digest.take(12).joinToString("") { "%02x".format(it) }
        return "owner_${namespace}_"
    }

    private fun readPoint(prefix: String, kind: String): HGeoPoint? {
        val latKey = "${prefix}${kind}_lat"
        val lonKey = "${prefix}${kind}_lon"
        if (!prefs.contains(latKey) || !prefs.contains(lonKey)) return null
        val lat = prefs.getString(latKey, null)?.toDoubleOrNull() ?: return null
        val lon = prefs.getString(lonKey, null)?.toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return HGeoPoint(lat, lon, prefs.getString("${prefix}${kind}_label", null))
    }

    private fun writePoint(kind: String, point: HGeoPoint?) {
        val prefix = prefix()
        val editor = prefs.edit()
        if (point == null) {
            editor
                .remove("${prefix}${kind}_lat")
                .remove("${prefix}${kind}_lon")
                .remove("${prefix}${kind}_label")
        } else {
            editor
                .putString("${prefix}${kind}_lat", point.latitude.toString())
                .putString("${prefix}${kind}_lon", point.longitude.toString())
                .putString("${prefix}${kind}_label", point.label)
        }
        editor.apply()
    }

    companion object {
        private const val PREFS = "h_location_scope_v1"
    }
}
