package com.malik.lmai.feature.ai

import com.malik.lmai.data.database.entity.PlatformV2
import com.malik.lmai.data.model.ClientType
import com.malik.lmai.data.repository.SettingRepository
import com.malik.lmai.feature.ai.openrouter.OpenRouterCredentialStore
import com.malik.lmai.feature.ai.openrouter.OpenRouterOAuthCoordinator
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ensures H's hidden cloud execution routes exist.
 *
 * H is always the single assistant identity. Provider routes are implementation details
 * used only to execute H's requests. Local/on-device AI routes are explicitly removed,
 * including legacy database entries left by older app versions.
 *
 * This class deliberately does not choose, enable, disable, or fail over providers.
 * Bootstrap owns configuration existence only; per-turn routing belongs exclusively to
 * SmartFreeAiOrchestrator/ProviderAgentGatewayRouter.
 */
@Singleton
class FreeAiBootstrapper @Inject constructor(
    private val settingRepository: SettingRepository,
    private val freeAiRouter: FreeAiRouter,
) {

    suspend fun ensureReady(): List<PlatformV2> =
        ensureBaselines(settingRepository.fetchPlatformV2s())

    private suspend fun ensureBaselines(platforms: List<PlatformV2>): List<PlatformV2> {
        var current = purgeLegacyLocalAiRoutes(platforms)

        for (route in BLOCKRUN_ROUTES) {
            val existing = current.firstOrNull { platform ->
                freeAiRouter.isInternalFree(platform) &&
                    freeAiRouter.detectProvider(platform) == FreeAiRouter.Provider.BLOCKRUN &&
                    platform.model == route.model
            }
            if (existing == null) {
                settingRepository.addPlatformV2(
                    PlatformV2(
                        name = route.name,
                        compatibleType = ClientType.CUSTOM,
                        enabled = false,
                        apiUrl = FreeAiRouter.BLOCKRUN_API_BASE,
                        token = null,
                        model = route.model,
                        provider = AiProviderOrigin.internalProviderCode("blockrun"),
                        isFree = true,
                        temperature = 0.7f,
                        topP = 0.95f,
                        stream = true,
                        reasoning = route.reasoning,
                        timeout = 120,
                    )
                )
                current = settingRepository.fetchPlatformV2s()
            } else if (existing.name != route.name) {
                settingRepository.updatePlatformV2(existing.copy(name = route.name))
                current = settingRepository.fetchPlatformV2s()
            }
        }

        val openRouterExisting = current.firstOrNull { platform ->
            freeAiRouter.isInternalFree(platform) &&
                freeAiRouter.detectProvider(platform) == FreeAiRouter.Provider.OPENROUTER
        }
        if (openRouterExisting == null) {
            settingRepository.addPlatformV2(
                PlatformV2(
                    name = H_OPENROUTER_DISPLAY_NAME,
                    compatibleType = ClientType.OPEN_ROUTER,
                    enabled = false,
                    apiUrl = OpenRouterOAuthCoordinator.API_URL,
                    token = OpenRouterCredentialStore.PLATFORM_TOKEN_SENTINEL,
                    model = OpenRouterOAuthCoordinator.FREE_MODEL,
                    provider = AiProviderOrigin.internalProviderCode("openrouter"),
                    isFree = true,
                    temperature = 0.7f,
                    topP = 0.95f,
                    stream = true,
                    reasoning = false,
                    timeout = 90,
                )
            )
            current = settingRepository.fetchPlatformV2s()
        } else if (openRouterExisting.name != H_OPENROUTER_DISPLAY_NAME) {
            settingRepository.updatePlatformV2(openRouterExisting.copy(name = H_OPENROUTER_DISPLAY_NAME))
            current = settingRepository.fetchPlatformV2s()
        }

        return current
    }

    private suspend fun purgeLegacyLocalAiRoutes(platforms: List<PlatformV2>): List<PlatformV2> {
        val legacyLocalRoutes = platforms.filter(::isLegacyLocalAiRoute)
        if (legacyLocalRoutes.isEmpty()) return platforms

        for (platform in legacyLocalRoutes) {
            runCatching { settingRepository.deletePlatformV2(platform) }
        }
        return settingRepository.fetchPlatformV2s()
    }

    private fun isLegacyLocalAiRoute(platform: PlatformV2): Boolean {
        val providerId = AiProviderOrigin.baseProviderId(platform.provider)
        val normalizedUrl = platform.apiUrl.trim().lowercase()
        val normalizedName = platform.name.trim().lowercase()
        val normalizedModel = platform.model.trim().lowercase()

        return providerId in LEGACY_LOCAL_PROVIDER_IDS ||
            normalizedUrl.startsWith("local://") ||
            normalizedUrl.contains("mediapipe") ||
            normalizedName == LEGACY_LOCAL_DISPLAY_NAME ||
            (platform.token.isNullOrBlank() && normalizedModel.contains("qwen2.5-0.5b"))
    }

    private data class BaselineRoute(
        val name: String,
        val model: String,
        val reasoning: Boolean = false,
    )

    companion object {
        const val H_OPENROUTER_DISPLAY_NAME = "مساعد H الرقمي · OpenRouter"

        const val BLOCKRUN_CODE_MODEL = "cohere/north-mini-code"
        const val BLOCKRUN_FAST_CODE_MODEL = "poolside/laguna-xs-2.1"
        const val BLOCKRUN_REASONING_MODEL = "nvidia/nemotron-3.5-lightning"

        private const val LEGACY_LOCAL_DISPLAY_NAME = "مساعد h الرقمي · محلي"
        private val LEGACY_LOCAL_PROVIDER_IDS = setOf(
            "local",
            "mediapipe",
            "qwenlocal",
            "aicore",
            "nano",
        )

        private val BLOCKRUN_ROUTES = listOf(
            BaselineRoute(
                name = "مساعد H الرقمي · برمجة",
                model = BLOCKRUN_CODE_MODEL,
            ),
            BaselineRoute(
                name = "مساعد H الرقمي · برمجة سريعة",
                model = BLOCKRUN_FAST_CODE_MODEL,
            ),
            BaselineRoute(
                name = "مساعد H الرقمي · تفكير",
                model = BLOCKRUN_REASONING_MODEL,
                reasoning = true,
            ),
        )
    }
}
