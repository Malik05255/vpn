package com.malik.lmai.feature.reminder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HLocationScopeTest {
    private val anchor = HGeoPoint(24.7136, 46.6753, "الرياض")

    @Test
    fun candidateInsideRadiusIsAccepted() {
        val config = HLocationScopeConfig(baseAnchor = anchor, radiusKm = 100.0)
        val nearby = HGeoPoint(24.7743, 46.7386, "قريب")

        val decision = HLocationScopePolicy.evaluateCandidate(config, nearby)

        assertEquals(HLocationScopeOutcome.IN_SCOPE, decision.outcome)
        assertTrue(decision.allowed)
        assertTrue((decision.distanceKm ?: 999.0) < 20.0)
    }

    @Test
    fun distantAmbiguousCandidateIsRejected() {
        val config = HLocationScopeConfig(baseAnchor = anchor, radiusKm = 100.0)
        val jeddah = HGeoPoint(21.5433, 39.1728, "جدة")

        val decision = HLocationScopePolicy.evaluateCandidate(config, jeddah)

        assertEquals(HLocationScopeOutcome.OUTSIDE_SCOPE, decision.outcome)
        assertFalse(decision.allowed)
        assertTrue((decision.distanceKm ?: 0.0) > 500.0)
    }

    @Test
    fun currentPositionOutsideBaseScopeRequiresAdjustment() {
        val config = HLocationScopeConfig(baseAnchor = anchor, radiusKm = 100.0)
        val jeddah = HGeoPoint(21.5433, 39.1728, "جدة")

        val decision = HLocationScopePolicy.evaluateCurrentPosition(config, jeddah)

        assertEquals(HLocationScopeOutcome.OUTSIDE_BASE_SCOPE, decision.outcome)
        assertFalse(decision.allowed)
        assertTrue(decision.needsScopeAdjustment)
    }

    @Test
    fun travelAnchorAllowsLocalRequestsOutsideBaseScope() {
        val jeddah = HGeoPoint(21.5433, 39.1728, "جدة")
        val config = HLocationScopeConfig(
            baseAnchor = anchor,
            radiusKm = 100.0,
            travelAnchor = jeddah,
        )
        val local = HGeoPoint(21.6000, 39.1800, "مكان في جدة")

        val decision = HLocationScopePolicy.evaluateCandidate(config, local)

        assertEquals(HLocationScopeOutcome.IN_SCOPE, decision.outcome)
        assertEquals(HLocationAnchorSource.TRAVEL, decision.anchorSource)
        assertTrue(decision.allowed)
    }

    @Test
    fun explicitDistantPlaceCanOverrideDefaultRadius() {
        val config = HLocationScopeConfig(baseAnchor = anchor, radiusKm = 100.0)
        val jeddah = HGeoPoint(21.5433, 39.1728, "جدة")

        val decision = HLocationScopePolicy.evaluateCandidate(
            config = config,
            candidate = jeddah,
            explicitDistantPlace = true,
        )

        assertEquals(HLocationScopeOutcome.EXPLICIT_OVERRIDE, decision.outcome)
        assertTrue(decision.allowed)
    }

    @Test
    fun noAnchorPreservesLegacyResolutionBehavior() {
        val config = HLocationScopeConfig()
        val candidate = HGeoPoint(10.0, 10.0)

        val decision = HLocationScopePolicy.evaluateCandidate(config, candidate)

        assertEquals(HLocationScopeOutcome.NO_ANCHOR, decision.outcome)
        assertTrue(decision.allowed)
    }
}
