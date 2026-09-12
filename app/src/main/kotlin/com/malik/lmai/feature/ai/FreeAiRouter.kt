package com.malik.lmai.feature.ai

import com.malik.lmai.data.database.entity.PlatformV2
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Legacy class name retained internally for database/API compatibility.
 * User-facing identity is always "مساعد H الرقمي" / المساعد الشخصي H.
 *
 * Providers are hidden execution backends for H, never separate assistant identities.
 * On-device/local AI runtimes are intentionally not supported.
 */
@Singleton
class FreeAiRouter @Inject constructor() {

    enum class Provider(
        val id: String,
        val priority: Int,
    ) {
        BLOCKRUN("blockrun", 0),
        OPENROUTER("openrouter", 1),
        GEMINI("gemini", 2),
        GROQ("groq", 3),
        MISTRAL("mistral", 4),
        CLOUDFLARE("cloudflare", 5),
        UNKNOWN("unknown", 99),
    }

    data class Candidate(
        val platform: PlatformV2,
        val provider: Provider,
    )

    fun orderedCandidates(platforms: List<PlatformV2>): List<Candidate> =
        platforms
            .mapNotNull { platform ->
                val provider = detectProvider(platform)
                if (!isFreeCandidate(platform, provider)) return@mapNotNull null
                Candidate(platform = platform, provider = provider)
            }
            .sortedWith(
                compareBy<Candidate> { it.provider.priority }
                    .thenBy { it.platform.name.lowercase() }
            )

    fun selectBest(platforms: List<PlatformV2>): PlatformV2? =
        orderedCandidates(platforms).firstOrNull()?.platform

    fun nextAfter(
        platforms: List<PlatformV2>,
        currentPlatformUid: String,
    ): PlatformV2? {
        val candidates = orderedCandidates(platforms)
        val currentIndex = candidates.indexOfFirst { it.platform.uid == currentPlatformUid }
        if (currentIndex < 0) return candidates.firstOrNull()?.platform
        return candidates.getOrNull(currentIndex + 1)?.platform
    }

    /**
     * Only a recognized, explicitly internal cloud backend belongs to H's free pool.
     * Unknown internal ids (including legacy internal:local) are rejected at the boundary
     * instead of being allowed to inherit INTERNAL_FREE status from their prefix alone.
     */
    fun isInternalFree(platform: PlatformV2): Boolean =
        AiProviderOrigin.of(platform) == AiProviderOrigin.INTERNAL_FREE &&
            detectProvider(platform) != Provider.UNKNOWN

    fun isExternal(platform: PlatformV2): Boolean =
        AiProviderOrigin.of(platform) == AiProviderOrigin.EXTERNAL

    fun detectProvider(platform: PlatformV2): Provider {
        val rawProvider = platform.provider?.trim()?.lowercase().orEmpty()
        val providerId = AiProviderOrigin.baseProviderId(platform.provider)
        explicitProvider(providerId)?.let { return it }

        // A namespaced provider id is an explicit trust boundary. If it is unknown,
        // do not infer a different provider from a misleading name, URL, or model.
        // This permanently blocks legacy internal:local/on-device routes from being
        // reclassified as a supported cloud route by fingerprinting.
        if (
            rawProvider.startsWith(INTERNAL_PROVIDER_PREFIX) ||
            rawProvider.startsWith(EXTERNAL_PROVIDER_PREFIX)
        ) {
            return Provider.UNKNOWN
        }

        val fingerprint = buildString {
            append(platform.name)
            append(' ')
            append(platform.apiUrl)
            append(' ')
            append(platform.model)
        }.lowercase()

        return when {
            "blockrun" in fingerprint -> Provider.BLOCKRUN
            "openrouter" in fingerprint -> Provider.OPENROUTER
            "gemini" in fingerprint || "googleapis.com" in fingerprint -> Provider.GEMINI
            "groq" in fingerprint -> Provider.GROQ
            "mistral" in fingerprint -> Provider.MISTRAL
            "cloudflare" in fingerprint || "workers.ai" in fingerprint -> Provider.CLOUDFLARE
            else -> Provider.UNKNOWN
        }
    }

    private fun explicitProvider(rawProvider: String?): Provider? {
        val normalized = rawProvider
            ?.trim()
            ?.lowercase()
            ?.replace("_", "")
            ?.replace("-", "")
            ?.replace(" ", "")
            ?.takeIf { it.isNotBlank() }
            ?: return null

        return when (normalized) {
            "blockrun" -> Provider.BLOCKRUN
            "openrouter" -> Provider.OPENROUTER
            "gemini", "google", "googleaistudio" -> Provider.GEMINI
            "groq" -> Provider.GROQ
            "mistral", "mistralai" -> Provider.MISTRAL
            "cloudflare", "cloudflareworkersai", "workersai" -> Provider.CLOUDFLARE
            else -> null
        }
    }

    fun isFreeCandidate(
        platform: PlatformV2,
        provider: Provider = detectProvider(platform),
    ): Boolean {
        if (!isInternalFree(platform)) return false
        if (provider == Provider.UNKNOWN) return false

        if (provider == Provider.BLOCKRUN) {
            val normalizedUrl = platform.apiUrl.trim().trimEnd('/').lowercase()
            return normalizedUrl == BLOCKRUN_API_BASE ||
                normalizedUrl.startsWith("$BLOCKRUN_API_BASE/")
        }

        return !platform.token.isNullOrBlank()
    }

    companion object {
        const val BLOCKRUN_API_BASE = "https://blockrun.ai/api"
        private const val INTERNAL_PROVIDER_PREFIX = "internal:"
        private const val EXTERNAL_PROVIDER_PREFIX = "external:"
    }
}
