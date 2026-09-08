package com.malik.lmai.feature.reminder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HLocationScopePolicyTest {
    private val riyadhScope = HLocationScope(
        enabled = true,
        anchorLatitude = 24.7136,
        anchorLongitude = 46.6753,
        anchorLabel = "الرياض",
        radiusKm = 100.0,
    )

    @Test fun defaultRadiusIsOneHundredKm() {
        assertEquals(100.0, HLocationScope().radiusKm, 0.0)
    }

    @Test fun candidateAboutFiftyKmAwayIsAllowed() {
        val result = HLocationScopePolicy.evaluate(riyadhScope, 25.16, 46.6753)
        assertTrue(result.configured)
        assertTrue(result.inside)
        assertTrue((result.distanceKm ?: 0.0) in 45.0..55.0)
    }

    @Test fun candidateBeyondOneHundredKmIsRejected() {
        val result = HLocationScopePolicy.evaluate(riyadhScope, 25.80, 46.6753)
        assertTrue(result.configured)
        assertFalse(result.inside)
        assertTrue((result.distanceKm ?: 0.0) > 100.0)
    }

    @Test fun unconfiguredScopeNeverSilentlyAllowsCandidate() {
        val result = HLocationScopePolicy.evaluate(HLocationScope(), 24.7136, 46.6753)
        assertFalse(result.configured)
        assertFalse(result.inside)
    }

    @Test fun activeTravelScopeReplacesBaseForSearchOnly() {
        val scope = riyadhScope.copy(
            travelEnabled = true,
            travelLatitude = 21.5433,
            travelLongitude = 39.1728,
            travelLabel = "جدة",
            travelRadiusKm = 100.0,
            travelExpiryMode = HTravelExpiryMode.HOURS_24,
            travelExpiresAtMs = 2_000L,
        )
        val effective = HLocationScopePolicy.effectiveScope(scope, 21.55, 39.18, nowMs = 1_000L)
        assertEquals(HLocationScopeSource.TRAVEL, effective.source)
        assertEquals("جدة", effective.label)
        assertTrue(HLocationScopePolicy.evaluateEffective(scope, 21.60, 39.20, 21.55, 39.18, 1_000L).inside)
        assertEquals(24.7136, scope.anchorLatitude!!, 0.0)
    }

    @Test fun expiredTravelScopeFallsBackToBase() {
        val scope = riyadhScope.copy(
            travelEnabled = true,
            travelLatitude = 21.5433,
            travelLongitude = 39.1728,
            travelExpiryMode = HTravelExpiryMode.HOURS_24,
            travelExpiresAtMs = 999L,
        )
        val effective = HLocationScopePolicy.effectiveScope(scope, 21.55, 39.18, nowMs = 1_000L)
        assertEquals(HLocationScopeSource.BASE, effective.source)
    }

    @Test fun untilReturnTravelStopsWhenCurrentLocationReentersBase() {
        val scope = riyadhScope.copy(
            travelEnabled = true,
            travelLatitude = 21.5433,
            travelLongitude = 39.1728,
            travelExpiryMode = HTravelExpiryMode.UNTIL_RETURN,
        )
        val away = HLocationScopePolicy.effectiveScope(scope, 21.55, 39.18, nowMs = 1_000L)
        val home = HLocationScopePolicy.effectiveScope(scope, 24.7136, 46.6753, nowMs = 1_000L)
        assertEquals(HLocationScopeSource.TRAVEL, away.source)
        assertEquals(HLocationScopeSource.BASE, home.source)
    }

    @Test fun evaluationDoesNotMovePinnedAnchor() {
        val original = riyadhScope
        HLocationScopePolicy.evaluate(original, 24.7136, 46.6753)
        HLocationScopePolicy.evaluate(original, 26.0, 50.0)
        assertEquals(24.7136, original.anchorLatitude!!, 0.0)
        assertEquals(46.6753, original.anchorLongitude!!, 0.0)
        assertEquals(100.0, original.radiusKm, 0.0)
    }

    @Test fun actualReminderGeofenceRemainsSmallByDefault() {
        val reminderLocation = HReminderLocation(placeNameAr = "يارا", latitude = 24.7136, longitude = 46.6753)
        assertEquals(180f, reminderLocation.radiusMeters, 0f)
    }
}
