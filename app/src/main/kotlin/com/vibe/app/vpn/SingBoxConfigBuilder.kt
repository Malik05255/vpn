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
        val sourceOutbound = json.parseToJsonElement(candidate.outboundJson).jsonObject
        require(sourceOutbound["tag"]?.toString()?.contains(OUTBOUND_TAG) == true) {
            "Candidate outbound is missing the managed tag"
        }

        // Proxy hostnames must be resolved before the proxy exists. Giving the outbound an explicit
        // local bootstrap resolver prevents the circular dependency where tunneled DNS needs the
        // proxy while the proxy itself still needs DNS. All device/app DNS remains on secure-dns.
        val outbound = buildJsonObject {
            sourceOutbound.forEach { (key, value) -> put(key, value) }
            put("domain_resolver", BOOTSTRAP_DNS_TAG)
            put("connect_timeout", "7s")
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
                            put("type", "local")
                            put("tag", BOOTSTRAP_DNS_TAG)
                        }
                    )
                    add(
                        buildJsonObject {
                            // DoH on 443 works through far more public HTTP/SOCKS relays than DoT
                            // on 853. The request itself is still detoured through country-proxy.
                            put("type", "https")
                            put("tag", DNS_TAG)
                            put("server", "1.1.1.1")
                            put("server_port", 443)
                            put("path", "/dns-query")
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
                put("default_domain_resolver", BOOTSTRAP_DNS_TAG)
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
    private const val BOOTSTRAP_DNS_TAG = "bootstrap-dns"
    private const val DNS_TAG = "secure-dns"
    private const val TUN_TAG = "country-tun"
}
