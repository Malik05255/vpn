package com.vibe.app.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyFallbackParserTest {
    @Test
    fun parsesHttpCountryProxyIntoSingBoxOutbound() {
        val candidate = ProxyShareParser.parse(
            "http://41.128.72.142:1976",
            sourceId = "proxyscrape-country",
        )

        assertNotNull(candidate)
        requireNotNull(candidate)
        assertEquals(ProxyProtocol.HTTP, candidate.protocol)
        assertEquals("41.128.72.142", candidate.server)
        assertEquals(1976, candidate.port)
        assertTrue(candidate.outboundJson.contains("\"type\":\"http\""))
        assertTrue(candidate.outboundJson.contains("\"tag\":\"country-proxy\""))
    }

    @Test
    fun parsesSocks5CountryProxyIntoSingBoxOutbound() {
        val candidate = ProxyShareParser.parse(
            "socks5://198.51.100.7:1080",
            sourceId = "country-feed",
        )

        assertNotNull(candidate)
        requireNotNull(candidate)
        assertEquals(ProxyProtocol.SOCKS5, candidate.protocol)
        assertTrue(candidate.outboundJson.contains("\"type\":\"socks\""))
        assertTrue(candidate.outboundJson.contains("\"version\":\"5\""))
    }

    @Test
    fun generatedHttpCandidateBuildsManagedTunConfig() {
        val candidate = requireNotNull(
            ProxyShareParser.parse(
                "http://41.65.103.190:8080",
                sourceId = "proxifly-country",
            )
        )

        val config = SingBoxConfigBuilder.build(candidate)

        assertTrue(config.contains("\"type\":\"tun\""))
        assertTrue(config.contains("\"type\":\"http\""))
        assertTrue(config.contains("\"final\":\"country-proxy\""))
    }
}
