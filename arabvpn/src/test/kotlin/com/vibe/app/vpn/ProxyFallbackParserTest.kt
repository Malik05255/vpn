package com.vibe.app.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun rawTcpVlessOmitsInvalidEmptyTransportObject() {
        val candidate = requireNotNull(
            ProxyShareParser.parse(
                "vless://7483fa54-0bbe-4382-ba8c-b445349962bf@91.228.227.112:110?security=none&encryption=none&headerType=none&type=tcp#tcp",
                sourceId = "country-feed",
            )
        )

        assertEquals(ProxyProtocol.VLESS, candidate.protocol)
        assertFalse(candidate.outboundJson.contains("\"transport\""))
    }

    @Test
    fun parsesRealityVlessAndNormalizesHtmlEntities() {
        val candidate = requireNotNull(
            ProxyShareParser.parse(
                "vless://4bf5a71c-d726-4585-b095-7396675706d5@72.56.81.165:40443?security=reality&amp;encryption=none&amp;pbk=D_ks4Yyk4-osnWBxCFvd0_UEgohUXvR2zJoWQg1CACU&amp;headerType=none&amp;fp=chrome&amp;type=tcp&amp;flow=xtls-rprx-vision&amp;sni=deepl.com&amp;sid=c84f#reality",
                sourceId = "argh73-country",
            )
        )

        assertEquals(ProxyProtocol.VLESS, candidate.protocol)
        assertTrue(candidate.outboundJson.contains("\"reality\""))
        assertTrue(candidate.outboundJson.contains("\"public_key\":\"D_ks4Yyk4-osnWBxCFvd0_UEgohUXvR2zJoWQg1CACU\""))
        assertTrue(candidate.outboundJson.contains("\"short_id\":\"c84f\""))
        assertTrue(candidate.outboundJson.contains("\"server_name\":\"deepl.com\""))
        assertFalse(candidate.outboundJson.contains("\"transport\""))
    }

    @Test
    fun generatedHttpCandidateBuildsManagedTunConfigWithDoh443AndBootstrapDns() {
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
        assertTrue(config.contains("\"type\":\"https\""))
        assertTrue(config.contains("\"server_port\":443"))
        assertTrue(config.contains("\"path\":\"/dns-query\""))
        assertTrue(config.contains("\"tag\":\"bootstrap-dns\""))
        assertTrue(config.contains("\"domain_resolver\":\"bootstrap-dns\""))
        assertFalse(config.contains("\"server_port\":853"))
    }
}
