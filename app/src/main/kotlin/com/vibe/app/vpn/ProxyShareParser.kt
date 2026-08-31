package com.vibe.app.vpn

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64

object ProxyShareParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(raw: String, sourceId: String): FreeVpnCandidate? = runCatching {
        val normalized = normalizeShareLink(raw)
        when {
            normalized.startsWith("trojan://", ignoreCase = true) -> parseTrojan(normalized, sourceId)
            normalized.startsWith("vless://", ignoreCase = true) -> parseVless(normalized, sourceId)
            normalized.startsWith("ss://", ignoreCase = true) -> parseShadowsocks(normalized, sourceId)
            normalized.startsWith("vmess://", ignoreCase = true) -> parseVmess(normalized, sourceId)
            normalized.startsWith("http://", ignoreCase = true) || normalized.startsWith("https://", ignoreCase = true) ->
                parseHttpProxy(normalized, sourceId)
            normalized.startsWith("socks5://", ignoreCase = true) -> parseSocksProxy(normalized, sourceId, version = "5")
            normalized.startsWith("socks4://", ignoreCase = true) -> parseSocksProxy(normalized, sourceId, version = "4")
            else -> null
        }
    }.getOrNull()

    private fun parseTrojan(raw: String, sourceId: String): FreeVpnCandidate? {
        val uri = URI(raw)
        val host = uri.host ?: return null
        val port = uri.port.takeIf { it in 1..65535 } ?: 443
        val password = uri.rawUserInfo?.percentDecode().orEmpty()
        if (password.isBlank()) return null
        val query = query(uri.rawQuery)
        val security = query["security"].orEmpty().lowercase()
        if (security.isNotEmpty() && security !in setOf("tls")) return null
        val sni = query["sni"] ?: query["peer"] ?: query["servername"]
        val transport = transportSelection(query) ?: return null
        val outbound = buildJsonObject {
            put("type", "trojan")
            put("tag", OUTBOUND_TAG)
            put("server", host)
            put("server_port", port)
            put("password", password)
            putJsonObject("tls") {
                put("enabled", true)
                if (!sni.isNullOrBlank()) put("server_name", sni)
                if (query.boolean("allowInsecure") || query.boolean("insecure")) put("insecure", true)
            }
            transport.json?.let { put("transport", it) }
        }
        return candidate(ProxyProtocol.TROJAN, host, port, outbound, sourceId, uri.rawFragment)
    }

    private fun parseVless(raw: String, sourceId: String): FreeVpnCandidate? {
        val uri = URI(raw)
        val host = uri.host ?: return null
        val port = uri.port.takeIf { it in 1..65535 } ?: 443
        val uuid = uri.rawUserInfo?.percentDecode().orEmpty()
        if (uuid.isBlank()) return null
        val query = query(uri.rawQuery)
        val security = query["security"].orEmpty().lowercase()
        if (security !in setOf("", "none", "false", "tls", "reality")) return null
        val transport = transportSelection(query) ?: return null

        if (transport.json == null && !legacyTcpHeaderSupported(query["headerType"] ?: query["header_type"])) {
            return null
        }

        val outbound = buildJsonObject {
            put("type", "vless")
            put("tag", OUTBOUND_TAG)
            put("server", host)
            put("server_port", port)
            put("uuid", uuid)
            query["flow"]?.takeIf(String::isNotBlank)?.let { put("flow", it) }

            when (security) {
                "tls" -> putJsonObject("tls") {
                    put("enabled", true)
                    (query["sni"] ?: query["servername"])
                        ?.takeIf(String::isNotBlank)
                        ?.let { put("server_name", it) }
                    addUtlsIfPresent(query)
                    if (query.boolean("allowInsecure") || query.boolean("insecure")) put("insecure", true)
                }

                "reality" -> {
                    val publicKey = (query["pbk"] ?: query["public_key"] ?: query["publicKey"])
                        ?.takeIf(String::isNotBlank)
                        ?: return null
                    val shortId = (query["sid"] ?: query["short_id"] ?: query["shortId"])
                        ?.takeIf(String::isNotBlank)
                    putJsonObject("tls") {
                        put("enabled", true)
                        (query["sni"] ?: query["servername"] ?: query["serverName"])
                            ?.takeIf(String::isNotBlank)
                            ?.let { put("server_name", it) }
                        addUtlsIfPresent(query)
                        putJsonObject("reality") {
                            put("enabled", true)
                            put("public_key", publicKey)
                            shortId?.let { put("short_id", it) }
                        }
                    }
                }
            }

            transport.json?.let { put("transport", it) }
        }
        return candidate(ProxyProtocol.VLESS, host, port, outbound, sourceId, uri.rawFragment)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.addUtlsIfPresent(query: Map<String, String>) {
        val fingerprint = (query["fp"] ?: query["fingerprint"])
            ?.trim()
            ?.takeIf { it.isNotEmpty() && !it.equals("randomized", ignoreCase = true) && !it.equals("random", ignoreCase = true) }
            ?: return
        putJsonObject("utls") {
            put("enabled", true)
            put("fingerprint", fingerprint)
        }
    }

    private fun parseShadowsocks(raw: String, sourceId: String): FreeVpnCandidate? {
        val uri = URI(raw)
        val query = query(uri.rawQuery)
        if (!query["plugin"].isNullOrBlank()) return null

        var host = uri.host
        var port = uri.port
        var credentials = uri.rawUserInfo?.percentDecode()

        if (!credentials.isNullOrBlank() && ':' !in credentials) {
            credentials = decodeBase64(credentials)
        }

        if (host == null || port !in 1..65535 || credentials.isNullOrBlank()) {
            val payload = raw.substringAfter("://").substringBefore('#').substringBefore('?')
            val decoded = decodeBase64(payload) ?: return null
            val at = decoded.lastIndexOf('@')
            if (at <= 0) return null
            credentials = decoded.substring(0, at)
            val endpoint = decoded.substring(at + 1)
            val colon = endpoint.lastIndexOf(':')
            if (colon <= 0) return null
            host = endpoint.substring(0, colon).removeSurrounding("[").removeSurrounding("]")
            port = endpoint.substring(colon + 1).toIntOrNull() ?: return null
        }

        val separator = credentials.indexOf(':')
        if (separator <= 0) return null
        val method = credentials.substring(0, separator)
        val password = credentials.substring(separator + 1)
        if (method.isBlank() || password.isBlank() || host.isNullOrBlank() || port !in 1..65535) return null

        val outbound = buildJsonObject {
            put("type", "shadowsocks")
            put("tag", OUTBOUND_TAG)
            put("server", host)
            put("server_port", port)
            put("method", method)
            put("password", password)
        }
        return candidate(ProxyProtocol.SHADOWSOCKS, host, port, outbound, sourceId, uri.rawFragment)
    }

    private fun parseVmess(raw: String, sourceId: String): FreeVpnCandidate? {
        val encoded = raw.substringAfter("://").substringBefore('#').trim()
        val decoded = decodeBase64(encoded) ?: return null
        val objectValue = json.parseToJsonElement(decoded).jsonObject
        val host = objectValue.string("add")
        val port = objectValue.string("port").toIntOrNull()
            ?: objectValue["port"]?.jsonPrimitive?.intOrNull
            ?: return null
        val uuid = objectValue.string("id")
        if (host.isBlank() || port !in 1..65535 || uuid.isBlank()) return null
        val network = objectValue.string("net").lowercase()
        val tlsValue = objectValue.string("tls").lowercase()
        val security = objectValue.string("scy").ifBlank { objectValue.string("security") }.ifBlank { "auto" }
        val alterId = objectValue.string("aid").toIntOrNull() ?: 0
        val transport = vmessTransport(objectValue, network) ?: return null

        if (transport.json == null && !legacyTcpHeaderSupported(objectValue.string("type"))) return null

        val outbound = buildJsonObject {
            put("type", "vmess")
            put("tag", OUTBOUND_TAG)
            put("server", host)
            put("server_port", port)
            put("uuid", uuid)
            put("security", security)
            if (alterId > 0) put("alter_id", alterId)
            if (tlsValue in setOf("tls", "true", "1")) {
                putJsonObject("tls") {
                    put("enabled", true)
                    objectValue.string("sni").ifBlank { objectValue.string("host") }
                        .takeIf(String::isNotBlank)
                        ?.let { put("server_name", it.substringBefore(',')) }
                    val fingerprint = objectValue.string("fp")
                    if (fingerprint.isNotBlank() && !fingerprint.equals("randomized", ignoreCase = true)) {
                        putJsonObject("utls") {
                            put("enabled", true)
                            put("fingerprint", fingerprint)
                        }
                    }
                    if (objectValue.string("skip-cert-verify").equals("true", ignoreCase = true)) {
                        put("insecure", true)
                    }
                }
            }
            transport.json?.let { put("transport", it) }
        }
        val name = objectValue.string("ps")
        return FreeVpnCandidate(
            protocol = ProxyProtocol.VMESS,
            server = host,
            port = port,
            outboundJson = outbound.toString(),
            sourceId = sourceId,
            displayName = name.ifBlank { "$host:$port" },
        )
    }

    private fun parseHttpProxy(raw: String, sourceId: String): FreeVpnCandidate? {
        val uri = URI(raw)
        val host = uri.host ?: return null
        val port = uri.port.takeIf { it in 1..65535 } ?: return null
        val credentials = uri.rawUserInfo?.percentDecode()
        val username = credentials?.substringBefore(':')?.takeIf(String::isNotBlank)
        val password = credentials?.substringAfter(':', "")?.takeIf(String::isNotBlank)
        val tlsToProxy = uri.scheme.equals("https", ignoreCase = true)

        val outbound = buildJsonObject {
            put("type", "http")
            put("tag", OUTBOUND_TAG)
            put("server", host)
            put("server_port", port)
            username?.let { put("username", it) }
            password?.let { put("password", it) }
            if (tlsToProxy) {
                putJsonObject("tls") {
                    put("enabled", true)
                    put("server_name", host)
                }
            }
        }
        return candidate(ProxyProtocol.HTTP, host, port, outbound, sourceId, uri.rawFragment)
    }

    private fun parseSocksProxy(raw: String, sourceId: String, version: String): FreeVpnCandidate? {
        val uri = URI(raw)
        val host = uri.host ?: return null
        val port = uri.port.takeIf { it in 1..65535 } ?: return null
        val credentials = uri.rawUserInfo?.percentDecode()
        val username = credentials?.substringBefore(':')?.takeIf(String::isNotBlank)
        val password = credentials?.substringAfter(':', "")?.takeIf(String::isNotBlank)
        val protocol = if (version == "5") ProxyProtocol.SOCKS5 else ProxyProtocol.SOCKS4

        val outbound = buildJsonObject {
            put("type", "socks")
            put("tag", OUTBOUND_TAG)
            put("server", host)
            put("server_port", port)
            put("version", version)
            username?.let { put("username", it) }
            if (version == "5") password?.let { put("password", it) }
        }
        return candidate(protocol, host, port, outbound, sourceId, uri.rawFragment)
    }

    /**
     * `json == null` means a valid raw TCP transport and therefore the `transport` field MUST be
     * omitted entirely. Returning `{}` here is invalid in sing-box 1.14 because a present V2Ray
     * transport object requires a concrete `type`.
     */
    private data class TransportSelection(val json: JsonObject?)

    private fun transportSelection(query: Map<String, String>): TransportSelection? {
        val type = query["type"].orEmpty().lowercase().ifBlank { "tcp" }
        return when (type) {
            "tcp", "raw" -> TransportSelection(null)
            "ws", "websocket" -> TransportSelection(
                buildJsonObject {
                    put("type", "ws")
                    query["path"]?.takeIf(String::isNotBlank)?.let { put("path", it) }
                    (query["host"] ?: query["authority"])
                        ?.takeIf(String::isNotBlank)
                        ?.let { host -> putJsonObject("headers") { put("Host", host) } }
                }
            )
            "httpupgrade", "http-upgrade", "http_upgrade" -> TransportSelection(
                buildJsonObject {
                    put("type", "httpupgrade")
                    query["path"]?.takeIf(String::isNotBlank)?.let { put("path", it) }
                    (query["host"] ?: query["authority"])
                        ?.takeIf(String::isNotBlank)
                        ?.let { put("host", it) }
                }
            )
            "grpc" -> TransportSelection(
                buildJsonObject {
                    put("type", "grpc")
                    (query["serviceName"] ?: query["service_name"] ?: query["path"]?.trimStart('/'))
                        ?.takeIf(String::isNotBlank)
                        ?.let { put("service_name", it) }
                }
            )
            "http", "h2", "http2" -> TransportSelection(
                buildJsonObject {
                    put("type", "http")
                    (query["host"] ?: query["authority"])
                        ?.takeIf(String::isNotBlank)
                        ?.let { value -> put("host", value.split(',').map(String::trim).filter(String::isNotEmpty).joinToString(",")) }
                    query["path"]?.takeIf(String::isNotBlank)?.let { put("path", it) }
                }
            )
            else -> null
        }
    }

    private fun vmessTransport(root: JsonObject, network: String): TransportSelection? = when (network.ifBlank { "tcp" }) {
        "tcp", "raw" -> TransportSelection(null)
        "ws" -> TransportSelection(
            buildJsonObject {
                put("type", "ws")
                root.string("path").takeIf(String::isNotBlank)?.let { put("path", it) }
                root.string("host").takeIf(String::isNotBlank)?.let { host ->
                    putJsonObject("headers") { put("Host", host.substringBefore(',')) }
                }
            }
        )
        "grpc" -> TransportSelection(
            buildJsonObject {
                put("type", "grpc")
                root.string("path").trimStart('/').takeIf(String::isNotBlank)?.let { put("service_name", it) }
            }
        )
        "http", "h2" -> TransportSelection(
            buildJsonObject {
                put("type", "http")
                root.string("path").takeIf(String::isNotBlank)?.let { put("path", it) }
                root.string("host").takeIf(String::isNotBlank)?.let { put("host", it.substringBefore(',')) }
            }
        )
        "httpupgrade" -> TransportSelection(
            buildJsonObject {
                put("type", "httpupgrade")
                root.string("path").takeIf(String::isNotBlank)?.let { put("path", it) }
                root.string("host").takeIf(String::isNotBlank)?.let { put("host", it.substringBefore(',')) }
            }
        )
        else -> null
    }

    private fun legacyTcpHeaderSupported(value: String?): Boolean = value
        .orEmpty()
        .trim()
        .lowercase()
        .let { it.isBlank() || it in setOf("none", "auto", "---") }

    private fun candidate(
        protocol: ProxyProtocol,
        host: String,
        port: Int,
        outbound: JsonObject,
        sourceId: String,
        rawFragment: String?,
    ): FreeVpnCandidate = FreeVpnCandidate(
        protocol = protocol,
        server = host,
        port = port,
        outboundJson = outbound.toString(),
        sourceId = sourceId,
        displayName = rawFragment?.percentDecode()?.take(80).orEmpty().ifBlank { "$host:$port" },
    )

    private fun query(rawQuery: String?): Map<String, String> = rawQuery.orEmpty()
        .split('&')
        .asSequence()
        .filter(String::isNotBlank)
        .map { item ->
            val index = item.indexOf('=')
            if (index < 0) item.percentDecode() to ""
            else item.substring(0, index).percentDecode() to item.substring(index + 1).percentDecode()
        }
        .associate { (key, value) -> key to value }

    private fun Map<String, String>.boolean(key: String): Boolean = this[key]
        ?.lowercase()
        ?.let { it in setOf("1", "true", "yes") }
        ?: false

    private fun JsonObject.string(key: String): String = this[key]
        ?.jsonPrimitive
        ?.contentOrNull
        .orEmpty()

    private fun normalizeShareLink(raw: String): String = raw
        .trim()
        .replace("&amp;", "&", ignoreCase = true)
        .replace("&#38;", "&", ignoreCase = true)

    private fun String.percentDecode(): String = URLDecoder.decode(
        replace("+", "%2B"),
        StandardCharsets.UTF_8.name(),
    )

    private fun decodeBase64(value: String): String? {
        val cleaned = value.trim().replace('-', '+').replace('_', '/')
        val padded = cleaned + "=".repeat((4 - cleaned.length % 4) % 4)
        return runCatching {
            String(Base64.getDecoder().decode(padded), StandardCharsets.UTF_8)
        }.getOrNull()
    }

    private const val OUTBOUND_TAG = "country-proxy"
}
