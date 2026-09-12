package com.malik.lmai.feature.ai

import com.malik.lmai.data.database.entity.PlatformV2

/**
 * Separates user-managed API providers from H's hidden cloud execution pool.
 *
 * Vendor identity and origin are different dimensions. An external Gemini API
 * and an internal/free Gemini route may coexist without sharing credentials,
 * quota, enabled state, model selection, or failure state.
 *
 * H is the only assistant identity. Internal providers are cloud execution backends,
 * never separate assistants. On-device/local AI providers are not supported.
 * Origin is encoded in PlatformV2.provider to avoid a Room schema migration.
 */
enum class AiProviderOrigin {
    INTERNAL_FREE,
    EXTERNAL;

    companion object {
        private const val INTERNAL_PREFIX = "internal:"
        private const val EXTERNAL_PREFIX = "external:"

        fun internalProviderCode(providerId: String): String =
            INTERNAL_PREFIX + normalizeProviderId(providerId)

        fun externalProviderCode(providerId: String): String =
            EXTERNAL_PREFIX + normalizeProviderId(providerId)

        fun baseProviderId(rawProvider: String?): String? {
            val raw = rawProvider?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
                ?: return null

            return when {
                raw.startsWith(INTERNAL_PREFIX) -> raw.removePrefix(INTERNAL_PREFIX)
                raw.startsWith(EXTERNAL_PREFIX) -> raw.removePrefix(EXTERNAL_PREFIX)
                else -> raw
            }.takeIf { it.isNotBlank() }
        }

        fun of(platform: PlatformV2): AiProviderOrigin {
            val raw = platform.provider?.trim()?.lowercase().orEmpty()
            return when {
                raw.startsWith(INTERNAL_PREFIX) -> INTERNAL_FREE
                raw.startsWith(EXTERNAL_PREFIX) -> EXTERNAL

                // Any legacy/unprefixed cloud API is treated as user-managed.
                // Local/on-device routes are removed by FreeAiBootstrapper migration.
                else -> EXTERNAL
            }
        }

        private fun normalizeProviderId(providerId: String): String =
            providerId.trim().lowercase().removePrefix(INTERNAL_PREFIX).removePrefix(EXTERNAL_PREFIX)
    }
}
