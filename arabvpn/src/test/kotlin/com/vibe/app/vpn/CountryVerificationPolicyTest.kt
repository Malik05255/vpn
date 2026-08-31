package com.vibe.app.vpn

import org.junit.Assert.assertEquals
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

    @Test
    fun parsesDocumentedPublicFeedIntoSupportedShareLinks() {
        val body = """
            {
              "data": [
                {
                  "host": "41.65.236.42",
                  "port": 1080,
                  "country_code": "EG",
                  "protocols": ["socks5", "http", "https"],
                  "socks5": 1,
                  "http": 1,
                  "ssl": 1
                },
                {
                  "ip": "196.200.1.20",
                  "port": 4145,
                  "country_code": "MA",
                  "protocols": ["socks4"]
                }
              ]
            }
        """.trimIndent()

        val lines = Socks5ProxiesPublicFeed.shareLines(body).toList()

        assertTrue("socks5://41.65.236.42:1080" in lines)
        assertTrue("http://41.65.236.42:1080" in lines)
        assertTrue("socks4://196.200.1.20:4145" in lines)
        assertFalse(lines.any { it.startsWith("https://") })
        assertEquals(3, lines.size)
    }
}
