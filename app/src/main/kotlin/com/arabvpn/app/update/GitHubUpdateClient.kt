package com.arabvpn.app.update

import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class GitHubUpdateClient {
    fun fetchLatestManifest(): UpdateManifest? {
        val releasesJson = getText(RELEASES_URL)
        val releases = JSONArray(releasesJson)

        for (index in 0 until releases.length()) {
            val release = releases.getJSONObject(index)
            if (release.optBoolean("draft", false) || release.optBoolean("prerelease", false)) continue
            val tag = release.optString("tag_name")
            if (!tag.startsWith(TAG_PREFIX)) continue

            val assets = release.optJSONArray("assets") ?: continue
            for (assetIndex in 0 until assets.length()) {
                val asset = assets.getJSONObject(assetIndex)
                if (asset.optString("name") == MANIFEST_ASSET_NAME) {
                    val url = asset.getString("browser_download_url")
                    return UpdateManifest.parse(getText(url))
                }
            }
        }
        return null
    }

    fun download(asset: UpdateAsset, destination: File, onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> }) {
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
        connection.connect()
        if (connection.responseCode !in 200..299) {
            val code = connection.responseCode
            connection.disconnect()
            error("Update server returned HTTP $code")
        }
        return connection
    }

    companion object {
        private const val TAG_PREFIX = "arab-vpn-v"
        private const val MANIFEST_ASSET_NAME = "update.json"
        private const val RELEASES_URL =
            "https://api.github.com/repos/Malik05255/vpn/releases?per_page=10"
        private const val CONNECT_TIMEOUT_MS = 12_000
        private const val READ_TIMEOUT_MS = 45_000
    }
}
