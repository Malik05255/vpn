package com.vibe.app.vpn

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

class IpLocationClient {
    suspend fun check(): IpLocation = withContext(Dispatchers.IO) {
        val connection = (URL(CHECK_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "ArabVPN/1.0")
        }

        try {
            val code = connection.responseCode
            require(code in 200..299) { "IP check failed with HTTP $code" }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val json = Json.parseToJsonElement(body).jsonObject
            val success = json["success"]?.jsonPrimitive?.contentOrNull
            require(success == null || success.equals("true", ignoreCase = true)) { "IP check service returned an error" }

            IpLocation(
                ip = json["ip"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                countryCode = json["country_code"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                country = json["country"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val CHECK_URL = "https://ipwho.is/"
    }
}

data class IpLocation(
    val ip: String,
    val countryCode: String,
    val country: String,
)
