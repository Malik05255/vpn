package com.vibe.app.vpn

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

object SingBoxConfigBuilder {
    private val json = Json { ignoreUnknownKeys = true }

    fun build(candidate: FreeVpnCandidate): String {
        val outbound = json.parseToJsonElement(candidate.outboundJson).jsonObject
        require(outbound["tag"]?.toString()?.contains(OUTBOUND_TAG) == true) {
            "Candidate outbound is missing the managed tag"
        }

        val root = buildJsonObject {
            putJsonObject("log") {
                put("level", "warn")
                put("timestamp", true)
            }

            putJsonObject("dns") {
                putJsonArray("servers") {
                    add(
                        buildJsonObject {
                            put("type", "tls")
                            put("tag", DNS_TAG)
                            put("server", "1.1.1.1")
                            put("server_port", 853)
                            put("detour", OUTBOUND_TAG)
                            putJsonObject("tls") {
                                put("enabled", true)
                                put("server_name", "cloudflare-dns.com")
                            }
                        }
                    )
                }
                put("final", DNS_TAG)
                put("strategy", "prefer_ipv4")
                put("disable_cache", false)
            }

            put(
                "inbounds",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "tun")
                            put("tag", TUN_TAG)
                            put("auto_route", true)
                            put("strict_route", true)
                            put("mtu", 1400)
                            put(
                                "address",
                                JsonArray(
                                    listOf(
                                        kotlinx.serialization.json.JsonPrimitive("172.19.0.1/30"),
                                        kotlinx.serialization.json.JsonPrimitive("fdfe:dcba:9876::1/126"),
                                    )
                                )
                            )
                            put("dns_mode", "hijack")
                            putJsonArray("dns_address") {
                                add("1.1.1.1")
                                add("2606:4700:4700::1111")
                            }
                            put("stack", "system")
                        }
                    )
                }
            )

            put("outbounds", JsonArray(listOf(outbound)))

            putJsonObject("route") {
                put("auto_detect_interface", true)
                put("final", OUTBOUND_TAG)
                put("default_domain_resolver", DNS_TAG)
                putJsonArray("rules") {
                    add(
                        buildJsonObject {
                            put("port", 53)
                            put("action", "hijack-dns")
                        }
                    )
                }
            }
        }
        return root.toString()
    }

    private const val OUTBOUND_TAG = "country-proxy"
    private const val DNS_TAG = "secure-dns"
    private const val TUN_TAG = "country-tun"
}
