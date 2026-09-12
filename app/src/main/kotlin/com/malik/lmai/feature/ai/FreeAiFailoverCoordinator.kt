package com.malik.lmai.feature.ai

import com.malik.lmai.data.database.entity.PlatformV2
import com.malik.lmai.data.repository.SettingRepository
import com.malik.lmai.feature.agent.AgentModelRequest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runtime failover for H.
 *
 * Provider selection is intentionally ephemeral. A transient timeout, rate limit, or
 * outage must never rewrite the user's persisted enabled-provider configuration.
 * H has no on-device/local AI fallback; all automatic execution routes are cloud based.
 */
@Singleton
class FreeAiFailoverCoordinator @Inject constructor(
    private val settingRepository: SettingRepository,
    private val freeAiRouter: FreeAiRouter,
    private val freeAiBootstrapper: FreeAiBootstrapper,
    private val smartOrchestrator: SmartFreeAiOrchestrator,
    private val runtimeAvailability: FreeAiRuntimeAvailability,
) {

    sealed class Result {
        data class Switched(
            val fromPlatformUid: String,
            val toPlatform: PlatformV2,
            val activatedFreeAi: Boolean,
        ) : Result()

        data object ManualMode : Result()
        data object FreeAiDisabled : Result()
        data object NoFallbackAvailable : Result()
    }

    /** Selects the best route for this turn without mutating saved provider state. */
    suspend fun resolveStartPlatform(request: AgentModelRequest): PlatformV2 {
        val platforms = freeAiBootstrapper.ensureReady()

        // An explicitly enabled user-managed API remains the user's first choice.
        platforms.firstOrNull { platform ->
            platform.enabled && freeAiRouter.isExternal(platform)
        }?.let { return it }

        val availability = runtimeAvailability.evaluate(platforms)
        return smartOrchestrator.selectBest(
            request = request,
            platforms = availability.usablePlatforms,
        ) ?: throw IllegalStateException(noRouteMessage(availability))
    }

    /** Legacy entry point kept for callers that do not yet provide a full request. */
    suspend fun resolveStartPlatform(requestedPlatform: PlatformV2): PlatformV2 {
        val platforms = freeAiBootstrapper.ensureReady()

        platforms.firstOrNull { platform ->
            platform.enabled && freeAiRouter.isExternal(platform)
        }?.let { return it }

        val availability = runtimeAvailability.evaluate(platforms)
        val usablePlatforms = availability.usablePlatforms

        usablePlatforms.firstOrNull { platform ->
            platform.uid == requestedPlatform.uid && freeAiRouter.isFreeCandidate(platform)
        }?.let { return it }

        return freeAiRouter.selectBest(usablePlatforms)
            ?: throw IllegalStateException(noRouteMessage(availability))
    }

    suspend fun handleFailure(
        failedPlatformUid: String,
        request: AgentModelRequest? = null,
        attemptedPlatformUids: Set<String> = emptySet(),
    ): Result {
        // Interactive turns should fail over once to a genuinely independent provider,
        // not hop through several sibling models behind the same failing backend.
        if (
            request != null &&
            request.tools.isEmpty() &&
            attemptedPlatformUids.size >= MAX_INTERACTIVE_PROVIDER_ATTEMPTS
        ) {
            return Result.NoFallbackAvailable
        }

        val platforms = freeAiBootstrapper.ensureReady()
        val availability = runtimeAvailability.evaluate(platforms)
        val usablePlatforms = availability.usablePlatforms
        val failedPlatform = platforms.firstOrNull { it.uid == failedPlatformUid }
        val failedWasInternal = failedPlatform?.let(freeAiRouter::isInternalFree) == true

        val excluded = buildSet {
            addAll(attemptedPlatformUids)
            add(failedPlatformUid)

            // For ordinary chat/knowledge turns, skip all sibling models belonging to
            // the same provider. A provider outage or quota problem is usually shared.
            if (request != null && request.tools.isEmpty() && failedPlatform != null) {
                val failedProvider = freeAiRouter.detectProvider(failedPlatform)
                usablePlatforms
                    .filter { platform ->
                        freeAiRouter.isInternalFree(platform) &&
                            freeAiRouter.detectProvider(platform) == failedProvider
                    }
                    .forEach { add(it.uid) }
            }
        }

        val target = when {
            request != null -> smartOrchestrator.selectBest(
                request = request,
                platforms = usablePlatforms,
                excludedPlatformUids = excluded,
            )

            failedWasInternal -> freeAiRouter.nextAfter(usablePlatforms, failedPlatformUid)
            else -> freeAiRouter.selectBest(usablePlatforms)
        }

        if (target == null) {
            return Result.NoFallbackAvailable
        }

        // Never call updatePlatformV2/activateOnly here. Failover belongs to this turn,
        // not to persistent user settings or the next conversation turn.
        return Result.Switched(
            fromPlatformUid = failedPlatformUid,
            toPlatform = target,
            activatedFreeAi = false,
        )
    }

    private fun noRouteMessage(
        availability: FreeAiRuntimeAvailability.Snapshot,
    ): String = when {
        !availability.networkAvailable ->
            "H_CLOUD_AI_OFFLINE: يحتاج المساعد الشخصي H إلى اتصال بالإنترنت لتشغيل المعالجة الذكية."

        availability.openRouterCredentialMissing ->
            "H_OPENROUTER_CREDENTIAL_MISSING: تعذر استخدام OpenRouter، وسيحاول المساعد الشخصي H بقية المسارات المتاحة تلقائيًا."

        else ->
            "H_NO_ROUTE: لا يوجد مسار معالجة متاح للمساعد الشخصي H حاليًا. سيعيد المحاولة تلقائيًا عند توفر مسار مناسب."
    }

    companion object {
        private const val MAX_INTERACTIVE_PROVIDER_ATTEMPTS = 2
    }
}
