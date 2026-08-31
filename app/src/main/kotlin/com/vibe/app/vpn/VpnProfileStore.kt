package com.vibe.app.vpn

import android.content.Context
import com.wireguard.config.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets

class VpnProfileStore(context: Context) {
    private val profileDir = File(context.filesDir, "vpn-profiles").apply { mkdirs() }

    fun hasProfile(country: VpnCountry): Boolean = profileFile(country).isFile

    fun load(country: VpnCountry): Config {
        val file = profileFile(country)
        require(file.isFile) { "VPN profile for ${country.displayNameEn} is not configured" }
        file.inputStream().use { return Config.parse(it) }
    }

    fun import(country: VpnCountry, source: InputStream) {
        val bytes = source.readLimited(MAX_PROFILE_BYTES)
        val text = bytes.toString(StandardCharsets.UTF_8).trim()
        validateFullTunnelProfile(text)

        // Parse with WireGuard itself before persisting anything.
        Config.parse(ByteArrayInputStream(text.toByteArray(StandardCharsets.UTF_8)))

        val target = profileFile(country)
        val temp = File(profileDir, ".${country.tunnelName}.tmp")
        temp.writeText(text + "\n", StandardCharsets.UTF_8)
        if (!temp.renameTo(target)) {
            target.writeText(text + "\n", StandardCharsets.UTF_8)
            temp.delete()
        }
    }

    fun remove(country: VpnCountry) {
        profileFile(country).delete()
    }

    private fun profileFile(country: VpnCountry) = File(profileDir, country.profileFileName)

    private fun validateFullTunnelProfile(text: String) {
        require(text.contains("[Interface]", ignoreCase = true)) { "Missing [Interface] section" }
        require(text.contains("[Peer]", ignoreCase = true)) { "Missing [Peer] section" }
        require(KEY_LINE.containsMatchIn(text)) { "Missing WireGuard private key" }
        require(ENDPOINT_LINE.containsMatchIn(text)) { "Missing WireGuard endpoint" }
        require(DNS_LINE.containsMatchIn(text)) { "A tunnel DNS server is required" }

        val allowed = ALLOWED_IPS_LINE.findAll(text)
            .flatMap { match -> match.groupValues[1].split(',').asSequence() }
            .map { it.trim() }
            .toSet()
        require("0.0.0.0/0" in allowed) {
            "Profile must route all IPv4 traffic through the VPN (AllowedIPs = 0.0.0.0/0)"
        }
    }

    private fun InputStream.readLimited(maxBytes: Int): ByteArray {
        val buffer = ByteArray(8 * 1024)
        val out = java.io.ByteArrayOutputStream()
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read <= 0) break
            total += read
            require(total <= maxBytes) { "WireGuard configuration is too large" }
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    companion object {
        private const val MAX_PROFILE_BYTES = 64 * 1024
        private val KEY_LINE = Regex("(?im)^\\s*PrivateKey\\s*=\\s*\\S+\\s*$")
        private val ENDPOINT_LINE = Regex("(?im)^\\s*Endpoint\\s*=\\s*\\S+\\s*$")
        private val DNS_LINE = Regex("(?im)^\\s*DNS\\s*=\\s*\\S+.*$")
        private val ALLOWED_IPS_LINE = Regex("(?im)^\\s*AllowedIPs\\s*=\\s*(.+)$")
    }
}
