package com.arabvpn.app.update

import android.content.Context
import com.vibe.app.BuildConfig
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class GitHubUpdateClient(
    private val context: Context? = null,
) {
    fun cachedManifest(): UpdateManifest? = context?.let(UpdateManifestCache::load)

    fun fetchLatestManifest(): UpdateManifest? {
        // Fast path: one fixed URL, one manifest request. The rolling release is updated by CI.
        val directUrl = if (BuildConfig.DEBUG) DEV_MANIFEST_URL else RELEASE_MANIFEST_URL
        runCatching { getText(directUrl) }
            .getOrNull()
            ?.let { raw -> return parseAndCache(raw) }

        // Compatibility/failure fallback while a rolling tag is being created or GitHub briefly
        // returns a stale edge response. This path is slower because it lists releases first.
        val releasesJson = getText(RELEASES_URL)
        val releases = JSONArray(releasesJson)
        val tagPrefix = if (BuildConfig.DEBUG) DEV_TAG_PREFIX else RELEASE_TAG_PREFIX

        for (index in 0 until releases.length()) {
            val release = releases.getJSONObject(index)
            if (release.optBoolean("draft", false)) continue
            val tag = release.optString("tag_name")
            if (!tag.startsWith(tagPrefix)) continue
            if (tag == DEV_ROLLING_TAG || tag == RELEASE_ROLLING_TAG) continue

            // Development APKs intentionally consume prerelease dev builds. Production APKs never do.
            if (!BuildConfig.DEBUG && release.optBoolean("prerelease", false)) continue

            val assets = release.optJSONArray("assets") ?: continue
            for (assetIndex in 0 until assets.length()) {
                val asset = assets.getJSONObject(assetIndex)
                if (asset.optString("name") == MANIFEST_ASSET_NAME) {
                    val url = asset.getString("browser_download_url")
                    return parseAndCache(getText(url))
                }
            }
        }
        return null
    }

    fun download(
        asset: UpdateAsset,
        destination: File,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ) {
        destination.parentFile?.mkdirs()
        val connection = open(asset.url)
        try {
            val total = connection.contentLengthLong.coerceAtLeast(asset.size)
            connection.inputStream.buffered().use { input ->
                destination.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var downloaded = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        onProgress(downloaded, total)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun parseAndCache(rawJson: String): UpdateManifest {
        val manifest = UpdateManifest.parse(rawJson)
        context?.let { UpdateManifestCache.save(it, rawJson, manifest) }
        return manifest
    }

    private fun getText(url: String): String {
        val connection = open(url)
        return try {
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "ArabVPN-Android-Updater")
        connection.setRequestProperty("Cache-Control", "no-cache")
        connection.connect()
        if (connection.responseCode !in 200..299) {
            val code = connection.responseCode
            connection.disconnect()
            error("Update server returned HTTP $code")
        }
        return connection
    }

    companion object {
        private const val DEV_TAG_PREFIX = "arab-vpn-dev-v"
        private const val RELEASE_TAG_PREFIX = "arab-vpn-v"
        private const val DEV_ROLLING_TAG = "arab-vpn-dev-latest"
        private const val RELEASE_ROLLING_TAG = "arab-vpn-latest"
        private const val MANIFEST_ASSET_NAME = "update.json"

        private const val DEV_MANIFEST_URL =
            "https://github.com/Malik05255/vpn/releases/download/arab-vpn-dev-latest/update.json"
        private const val RELEASE_MANIFEST_URL =
            "https://github.com/Malik05255/vpn/releases/download/arab-vpn-latest/update.json"
        private const val RELEASES_URL =
            "https://api.github.com/repos/Malik05255/vpn/releases?per_page=20"

        // Keep failure fast. The slower fallback still has a chance, while cached data can render
        // immediately in the UI without making the user stare at an empty screen.
        private const val CONNECT_TIMEOUT_MS = 4_000
        private const val READ_TIMEOUT_MS = 8_000
    }
}
