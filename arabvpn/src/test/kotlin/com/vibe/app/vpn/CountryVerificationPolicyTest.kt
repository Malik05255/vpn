package com.vibe.app.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CountryVerificationPolicyTest {
    @Test
    fun acceptsWhenAvailableProvidersAgreeWithExpectedCountry() {
        assertTrue(CountryVerificationPolicy.accept("EG", "eg", "EG"))
        assertTrue(CountryVerificationPolicy.accept("JO", "JO", null))
        assertTrue(CountryVerificationPolicy.accept("MA", null, "ma"))
    }

    @Test
    fun rejectsWhenProvidersDisagree() {
        assertFalse(CountryVerificationPolicy.accept("JO", "JO", "US"))
        assertFalse(CountryVerificationPolicy.accept("EG", "DE", "EG"))
    }

    @Test
    fun rejectsWrongOrMissingCountryEvidence() {
        assertFalse(CountryVerificationPolicy.accept("MA", "FR", "FR"))
        assertFalse(CountryVerificationPolicy.accept("EG", null, null))
        assertFalse(CountryVerificationPolicy.accept("JO", "", null))
    }
}
