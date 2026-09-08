package com.malik.lmai.feature.reminder

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HLocationScopeBoundsTest {
    @Test
    fun candidateAtAboutNinetyKmIsInsideHundredKmScope() {
        val anchor = HGeoPoint(24.7136, 46.6753)
        val candidate = HGeoPoint(25.52, 46.6753)
        assertTrue(HLocationScopePolicy.evaluateCandidate(HLocationScopeConfig(anchor, 100.0), candidate).allowed)
    }

    @Test
    fun candidatePastHundredKmIsOutsideHundredKmScope() {
        val anchor = HGeoPoint(24.7136, 46.6753)
        val candidate = HGeoPoint(25.75, 46.6753)
        assertFalse(HLocationScopePolicy.evaluateCandidate(HLocationScopeConfig(anchor, 100.0), candidate).allowed)
    }
}
