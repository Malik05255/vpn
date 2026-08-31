package com.arabvpn.app.update

import org.json.JSONObject

data class UpdateAsset(
    val url: String,
    val sha256: String,
    val size: Long,
)

data class DeltaPatch(
    val fromVersionCode: Int,
    val asset: UpdateAsset,
)

data class UpdateManifest(
    val versionCode: Int,
    val versionName: String,
    val full: UpdateAsset,
    val patches: List<DeltaPatch>,
) {
    fun bestAssetFor(currentVersionCode: Int): DownloadPlan {
        val patch = patches
            .firstOrNull { it.fromVersionCode == currentVersionCode }
            ?.takeIf { it.asset.size > 0L && it.asset.size < full.size }
        return if (patch != null) {
            DownloadPlan.Delta(patch.asset)
        } else {
            DownloadPlan.Full(full)
        }
    }

    companion object {
        fun parse(json: String): UpdateManifest {
            val root = JSONObject(json)
            val fullObject = root.getJSONObject("full")
            val full = fullObject.toAsset()
            val patchArray = root.optJSONArray("patches")
            val patches = buildList {
                if (patchArray != null) {
                    for (index in 0 until patchArray.length()) {
                        val item = patchArray.getJSONObject(index)
                        add(
                            DeltaPatch(
                                fromVersionCode = item.getInt("fromVersionCode"),
                                asset = item.toAsset(),
                            )
                        )
                    }
                }
            }
            return UpdateManifest(
                versionCode = root.getInt("versionCode"),
                versionName = root.getString("versionName"),
                full = full,
                patches = patches,
            )
        }

        private fun JSONObject.toAsset(): UpdateAsset = UpdateAsset(
            url = getString("url"),
            sha256 = getString("sha256").lowercase(),
            size = getLong("size"),
        )
    }
}

sealed interface DownloadPlan {
    val asset: UpdateAsset

    data class Delta(override val asset: UpdateAsset) : DownloadPlan
    data class Full(override val asset: UpdateAsset) : DownloadPlan
}
