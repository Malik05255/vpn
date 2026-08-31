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
        when {
            raw.startsWith("trojan://", ignoreCase = true) -> parseTrojan(raw, sourceId)
            raw.startsWith("vless://", ignoreCase = true) -> parseVless(raw, sourceId)
            raw.startsWith("ss://", ignoreCase = true) -> parseShadowsocks(raw, sourceId)
            raw.startsWith("vmess://", ignoreCase = true) -> parseVmess(raw, sourceId)
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
        val transport = transportJson(query) ?: return null
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
            transport?.let { put("transport", it) }
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
        if (security == "reality") return null // fail closed; don't silently miscompile Reality options.
        if (security !in setOf("", "none", "false", "tls")) return null
        val transport = transportJson(query) ?: return null
        val outbound = buildJsonObject {
            put("type", "vless")
            put("tag", OUTBOUND_TAG)
            put("server", host)
            put("server_port", port)
            put("uuid", uuid)
            query["flow"]?.takeIf(String::isNotBlank)?.let { put("flow", it) }
            if (security == "tls") {
                putJsonObject("tls") {
                    put("enabled", true)
                    (query["sni"] ?: query["servername"])
                        ?.takeIf(String::isNotBlank)
                        ?.let { put("server_name", it) }
                    if (query.boolean("allowInsecure") || query.boolean("insecure")) put("insecure", true)
                }
            }
            transport?.let { put("transport", it) }
        }
        return candidate(ProxyProtocol.VLESS, host, port, outbound, sourceId, uri.rawFragment)
    }

    private fun parseShadowsocks(raw: String, sourceId: String): FreeVpnCandidate? {
        val uri = URI(raw)
        val query = query(uri.rawQuery)
        if (!query["plugin"].isNullOrBlank()) return null

        var host = uri.host
        var port = uri.port
        var credentials = uri.rawUserInfo?.percentDecode()

        // SIP002 allows user-info to be URL-safe base64(method:password).
        if (!credentials.isNullOrBlank() && ':' !in credentials) {
            credentials = decodeBase64(credentials)
        }

        // Legacy form: ss://BASE64(method:password@host:port)
        if (host == null || port !in 1..65535 || credentials.isNullOrBlank()) {
            val payload = raw.removePrefix("ss://").substringBefore('#').substringBefore('?')
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
        val encoded = raw.removePrefix("vmess://").substringBefore('#').trim()
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
        val security = objectValue.string("scy").ifBlank { "auto" }
        val alterId = objectValue.string("aid").toIntOrNull() ?: 0
        val transport = vmessTransport(objectValue, network) ?: return null
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
                }
            }
            transport?.let { put("transport", it) }
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

    /** null return means unsupported; JsonObject? inside Result semantics allows TCP/no transport. */
    private fun transportJson(query: Map<String, String>): JsonObject? {
        val type = query["type"].orEmpty().lowercase().ifBlank { "tcp" }
        return when (type) {
            "tcp", "raw" -> JsonObject(emptyMap())
            "ws", "websocket" -> buildJsonObject {
                put("type", "ws")
                query["path"]?.takeIf(String::isNotBlank)?.let { put("path", it) }
                (query["host"] ?: query["authority"])
                    ?.takeIf(String::isNotBlank)
                    ?.let { host ->
                        putJsonObject("headers") { put("Host", host) }
                    }
            }
            "httpupgrade", "http-upgrade", "http_upgrade" -> buildJsonObject {
                put("type", "httpupgrade")
                query["path"]?.takeIf(String::isNotBlank)?.let { put("path", it) }
                (query["host"] ?: query["authority"])
                    ?.takeIf(String::isNotBlank)
                    ?.let { put("host", it) }
            }
            "grpc" -> buildJsonObject {
                put("type", "grpc")
                (query["serviceName"] ?: query["service_name"] ?: query["path"]?.trimStart('/'))
                    ?.takeIf(String::isNotBlank)
                    ?.let { put("service_name", it) }
            }
            "http", "h2", "http2" -> buildJsonObject {
                put("type", "http")
                (query["host"] ?: query["authority"])
                    ?.takeIf(String::isNotBlank)
                    ?.let { value -> put("host", value.split(',').map(String::trim).filter(String::isNotEmpty).joinToString(",")) }
                query["path"]?.takeIf(String::isNotBlank)?.let { put("path", it) }
            }
            else -> return null
        }
    }

    private fun vmessTransport(root: JsonObject, network: String): JsonObject? = when (network.ifBlank { "tcp" }) {
        "tcp", "raw" -> JsonObject(emptyMap())
        "ws" -> buildJsonObject {
            put("type", "ws")
            root.string("path").takeIf(String::isNotBlank)?.let { put("path", it) }
            root.string("host").takeIf(String::isNotBlank)?.let { host ->
                putJsonObject("headers") { put("Host", host.substringBefore(',')) }
            }
        }
        "grpc" -> buildJsonObject {
            put("type", "grpc")
            root.string("path").trimStart('/').takeIf(String::isNotBlank)?.let { put("service_name", it) }
        }
        "http", "h2" -> buildJsonObject {
            put("type", "http")
            root.string("path").takeIf(String::isNotBlank)?.let { put("path", it) }
            root.string("host").takeIf(String::isNotBlank)?.let { put("host", it.substringBefore(',')) }
        }
        else -> null
    }

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
