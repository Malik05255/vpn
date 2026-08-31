package com.vibe.app.vpn

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

class IpLocationClient {
    /**
     * If an expected country is supplied, do not stop at the first healthy GeoIP provider when it
     * disagrees. Try the independent fallback first. This avoids rejecting a real tunnel because a
     * single GeoIP database has stale country ownership data for the exit IP.
     */
    suspend fun check(expectedCountryCode: String? = null): IpLocation = withContext(Dispatchers.IO) {
        var lastError: Throwable? = null
        var firstValid: IpLocation? = null
        for (provider in providers) {
            val result = runCatching { provider() }
            val location = result.getOrNull()
                ?.takeIf { it.ip.isNotBlank() && it.countryCode.isNotBlank() }
            if (location != null) {
                if (firstValid == null) firstValid = location
                if (
                    expectedCountryCode.isNullOrBlank() ||
                    location.countryCode.equals(expectedCountryCode, ignoreCase = true)
                ) {
                    return@withContext location
                }
            }
            lastError = result.exceptionOrNull() ?: lastError
        }
        firstValid?.let { return@withContext it }
        throw lastError ?: IllegalStateException("تعذر التحقق من عنوان IP الخارجي.")
    }

    private val providers: List<() -> IpLocation> = listOf(
        ::checkIpWho,
        ::checkIpApi,
    )

    private fun checkIpWho(): IpLocation {
        val json = readJson(IPWHO_URL)
        val success = json["success"]?.jsonPrimitive?.contentOrNull
        require(success == null || success.equals("true", ignoreCase = true)) {
            "IP check service returned an error"
        }
        return IpLocation(
            ip = json.string("ip"),
            countryCode = json.string("country_code"),
            country = json.string("country"),
            city = json.string("city"),
            latitude = json.double("latitude"),
            longitude = json.double("longitude"),
        )
    }

    private fun checkIpApi(): IpLocation {
        val json = readJson(IPAPI_URL)
        return IpLocation(
            ip = json.string("ip"),
            countryCode = json.string("country_code"),
            country = json.string("country_name"),
            city = json.string("city"),
            latitude = json.double("latitude"),
            longitude = json.double("longitude"),
        )
    }

    private fun readJson(url: String): JsonObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
            requestMethod = "GET"
            useCaches = false
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("User-Agent", "ArabVPN/1.2 Android")
        }
        return try {
            val code = connection.responseCode
            require(code in 200..299) { "IP check failed with HTTP $code" }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            Json.parseToJsonElement(body).jsonObject
        } finally {
            connection.disconnect()
        }
    }

    private fun JsonObject.string(key: String): String = this[key]
        ?.jsonPrimitive
        ?.contentOrNull
        .orEmpty()

    private fun JsonObject.double(key: String): Double? = this[key]
        ?.jsonPrimitive
        ?.doubleOrNull

    companion object {
        private const val IPWHO_URL = "https://ipwho.is/"
        private const val IPAPI_URL = "https://ipapi.co/json/"
    }
}

data class IpLocation(
    val ip: String,
    val countryCode: String,
    val country: String,
    val city: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
)
