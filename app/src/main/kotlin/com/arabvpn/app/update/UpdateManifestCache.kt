package com.arabvpn.app.update

import android.content.Context

/**
 * Tiny local cache used only to surface a previously discovered update instantly on app open.
 * The network remains the source of truth and refreshes this value in the background.
 */
object UpdateManifestCache {
    private const val PREFS = "arab_vpn_update_cache"
    private const val KEY_MANIFEST_JSON = "manifest_json"

    fun load(context: Context): UpdateManifest? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MANIFEST_JSON, null)
            ?: return null
        return runCatching { UpdateManifest.parse(raw) }.getOrNull()
    }

    fun save(context: Context, rawJson: String, manifest: UpdateManifest) {
        val current = load(context)
        // Never replace a newer cached manifest with an older/stale response.
        if (current != null && current.versionCode > manifest.versionCode) return

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MANIFEST_JSON, rawJson)
            .apply()
    }
}
