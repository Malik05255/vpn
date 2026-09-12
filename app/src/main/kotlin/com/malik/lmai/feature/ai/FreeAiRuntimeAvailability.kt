package com.malik.lmai.feature.ai

import com.malik.lmai.data.database.entity.PlatformV2
import com.malik.lmai.feature.ai.openrouter.OpenRouterCredentialStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runtime validation for H's hidden cloud execution routes.
 *
 * H no longer owns or prepares any on-device/local AI model. Built-in routes require
 * validated internet; providers remain implementation details behind the single H identity.
 */
@Singleton
class FreeAiRuntimeAvailability @Inject constructor(
    private val freeAiRouter: FreeAiRouter,
    private val openRouterCredentialStore: OpenRouterCredentialStore,
    private val networkAvailability: NetworkAvailability,
) {

    data class Snapshot(
        val usablePlatforms: List<PlatformV2>,
        val networkAvailable: Boolean,
        val openRouterCredentialMissing: Boolean,
    ) {
        val hasUsableInternalFreeRoute: Boolean
            get() = usablePlatforms.any { platform ->
                AiProviderOrigin.of(platform) == AiProviderOrigin.INTERNAL_FREE
            }
    }

    suspend fun evaluate(platforms: List<PlatformV2>): Snapshot {
        val networkAvailable = networkAvailability.hasValidatedInternet()
        var openRouterCredentialMissing = false

        val usable = ArrayList<PlatformV2>(platforms.size)
        for (platform in platforms) {
            if (!freeAiRouter.isInternalFree(platform)) {
                usable += platform
                continue
            }

            val provider = freeAiRouter.detectProvider(platform)
            val isUsable = when (provider) {
                FreeAiRouter.Provider.OPENROUTER -> {
                    if (!networkAvailable) {
                        false
                    } else if (platform.token == OpenRouterCredentialStore.PLATFORM_TOKEN_SENTINEL) {
                        val credentialPresent = !openRouterCredentialStore
                            .getApiKey()
                            .isNullOrBlank()
                        if (!credentialPresent) openRouterCredentialMissing = true
                        credentialPresent
                    } else {
                        freeAiRouter.isFreeCandidate(platform, provider)
                    }
                }

                else -> networkAvailable && freeAiRouter.isFreeCandidate(platform, provider)
            }

            if (isUsable) usable += platform
        }

        return Snapshot(
            usablePlatforms = usable,
            networkAvailable = networkAvailable,
            openRouterCredentialMissing = openRouterCredentialMissing,
        )
    }
}
