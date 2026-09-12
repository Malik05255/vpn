package com.malik.lmai.feature.agent.loop

import com.malik.lmai.data.database.entity.PlatformV2
import com.malik.lmai.data.model.ClientType
import com.malik.lmai.feature.agent.AgentModelEvent
import com.malik.lmai.feature.agent.AgentModelGateway
import com.malik.lmai.feature.agent.AgentModelRequest
import com.malik.lmai.feature.ai.FreeAiFailoverCoordinator
import com.malik.lmai.feature.ai.FreeAiRouter
import com.malik.lmai.feature.ai.ProviderHealthTracker
import com.malik.lmai.feature.ai.openrouter.OpenRouterCredentialStore
import com.malik.lmai.feature.assistant.HAssistantContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Single routing gateway for المساعد الشخصي H / مساعد H الرقمي.
 *
 * H is the only assistant identity. Explicit user-managed APIs and built-in cloud
 * routes are execution backends only; no on-device/local model is part of H.
 */
@Singleton
class ProviderAgentGatewayRouter @Inject constructor(
    private val qwenGateway: QwenChatCompletionsAgentGateway,
    private val openAiResponsesGateway: OpenAiResponsesAgentGateway,
    private val failoverCoordinator: FreeAiFailoverCoordinator,
    private val freeAiRouter: FreeAiRouter,
    private val providerHealthTracker: ProviderHealthTracker,
    private val openRouterCredentialStore: OpenRouterCredentialStore,
    private val mohammedAssistantContext: HAssistantContext,
) : AgentModelGateway {

    override suspend fun streamTurn(
        request: AgentModelRequest,
    ): Flow<AgentModelEvent> = flow {
        val preparedRequest = runCatching {
            mohammedAssistantContext.prepare(request)
        }.getOrDefault(request)

        val userFacingRequest = ChatTurnPolicy.adapt(preparedRequest)
        val turnMode = ChatTurnPolicy.detect(userFacingRequest)
        val turnStartedAtNs = System.nanoTime()

        fun turnElapsedMs(): Long =
            ((System.nanoTime() - turnStartedAtNs) / 1_000_000L)
                .coerceAtLeast(0L)

        val startPlatform = try {
            failoverCoordinator.resolveStartPlatform(userFacingRequest)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(
                AgentModelEvent.Failed(
                    message = e.message
                        ?.takeIf { it.isNotBlank() }
                        ?: "لا يوجد مسار متاح لالمساعد الشخصي H حاليًا."
                )
            )
            return@flow
        }

        var activeRequest = userFacingRequest.withPlatform(startPlatform)
        val attemptedPlatformUids = linkedSetOf<String>()

        while (true) {
            val platform = activeRequest.platform

            if (!attemptedPlatformUids.add(platform.uid)) {
                emit(
                    AgentModelEvent.Failed(
                        message = "توقف التحويل التلقائي لالمساعد الشخصي H لمنع تكرار نفس المسار."
                    )
                )
                return@flow
            }

            var failureMessage: String? = null
            var completed = false
            var visibleOrActionOutputEmitted = false
            var firstOutputRecorded = false
            var rateLimited = false
            val attemptStartedAtNs = System.nanoTime()

            fun elapsedMs(): Long =
                ((System.nanoTime() - attemptStartedAtNs) / 1_000_000L)
                    .coerceAtLeast(0L)

            fun recordFirstVisibleOutput() {
                if (firstOutputRecorded) return
                firstOutputRecorded = true
                providerHealthTracker.recordFirstOutput(
                    platformUid = platform.uid,
                    latencyMs = elapsedMs(),
                )
            }

            fun noteFailure(message: String) {
                failureMessage = message
                if (message.isRateLimitFailure()) {
                    rateLimited = true
                }
            }

            try {
                val providerFlow: Flow<AgentModelEvent>? = when {
                    isOpenRouterPlatform(platform) && activeRequest.hasImageAttachments() ->
                        openAiResponsesGateway.streamTurn(activeRequest)

                    isOpenAiCompatible(platform.compatibleType) ->
                        qwenGateway.streamTurn(activeRequest)

                    else -> null
                }

                if (providerFlow == null) {
                    noteFailure(unsupportedProviderMessage(platform.compatibleType))
                } else {
                    // Interactive cloud replies use coordinated limits:
                    // - one provider gets at most 5 seconds to produce first visible text;
                    // - all automatic provider attempts together share a 12-second budget.
                    // A route that stays silent longer is treated as unhealthy for this turn
                    // so the user reaches a responsive fallback instead of waiting on it.
                    val enforceInteractiveFirstOutputDeadline =
                        turnMode != ChatTurnMode.APP_EXECUTION &&
                            freeAiRouter.isInternalFree(platform) &&
                            !activeRequest.hasImageAttachments()

                    coroutineScope {
                        val eventChannel = providerFlow.produceIn(this)
                        try {
                            while (true) {
                                val received = if (
                                    enforceInteractiveFirstOutputDeadline &&
                                    !firstOutputRecorded
                                ) {
                                    val providerRemainingMs =
                                        INTERACTIVE_PROVIDER_FIRST_OUTPUT_TIMEOUT_MS - elapsedMs()
                                    val turnRemainingMs =
                                        INTERACTIVE_TOTAL_FIRST_OUTPUT_TIMEOUT_MS - turnElapsedMs()
                                    val remainingMs = minOf(providerRemainingMs, turnRemainingMs)

                                    if (remainingMs <= 0L) {
                                        null
                                    } else {
                                        withTimeoutOrNull(remainingMs) {
                                            eventChannel.receiveCatching()
                                        }
                                    }
                                } else {
                                    eventChannel.receiveCatching()
                                }

                                if (received == null) {
                                    noteFailure(
                                        "H_FIRST_OUTPUT_TIMEOUT: provider produced no visible output before interactive deadline"
                                    )
                                    eventChannel.cancel()
                                    break
                                }

                                val event = received.getOrNull() ?: break

                                when (event) {
                                    is AgentModelEvent.Failed -> noteFailure(event.message)

                                    is AgentModelEvent.Completed -> {
                                        if (
                                            !firstOutputRecorded &&
                                            !event.finalText.isNullOrBlank()
                                        ) {
                                            recordFirstVisibleOutput()
                                        }
                                        completed = true
                                        emit(event)
                                    }

                                    is AgentModelEvent.OutputDelta -> {
                                        if (event.delta.isNotEmpty()) {
                                            recordFirstVisibleOutput()
                                            visibleOrActionOutputEmitted = true
                                        }
                                        emit(event)
                                    }

                                    is AgentModelEvent.ToolCallReady -> {
                                        visibleOrActionOutputEmitted = true
                                        emit(event)
                                    }

                                    is AgentModelEvent.ThinkingDelta -> emit(event)
                                }

                                if (completed || failureMessage != null) {
                                    eventChannel.cancel()
                                    break
                                }
                            }
                        } finally {
                            eventChannel.cancel()
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                noteFailure(
                    e.message
                        ?.takeIf { it.isNotBlank() }
                        ?: e::class.java.simpleName
                            .takeIf { it.isNotBlank() }
                        ?: "تعذر تشغيل مسار المساعد الشخصي H الحالي."
                )
            }

            val elapsedMs = elapsedMs()

            if (completed) {
                providerHealthTracker.recordSuccess(platform.uid, elapsedMs)
                return@flow
            }

            if (rateLimited) {
                providerHealthTracker.recordRateLimit(platform.uid)
            } else {
                providerHealthTracker.recordFailure(platform.uid)
            }

            val terminalFailure = failureMessage
                ?: "انتهى المسار بدون إكمال الرد."

            if (visibleOrActionOutputEmitted) {
                emit(AgentModelEvent.Failed(message = terminalFailure))
                return@flow
            }

            // Do not start another interactive cloud attempt after the shared latency
            // budget has already been consumed. This bounds total perceived waiting.
            if (
                turnMode != ChatTurnMode.APP_EXECUTION &&
                turnElapsedMs() >= INTERACTIVE_TOTAL_FIRST_OUTPUT_TIMEOUT_MS
            ) {
                emit(AgentModelEvent.Failed(message = terminalFailure))
                return@flow
            }

            var failover = try {
                failoverCoordinator.handleFailure(
                    failedPlatformUid = platform.uid,
                    request = activeRequest,
                    attemptedPlatformUids = attemptedPlatformUids,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emit(AgentModelEvent.Failed(message = terminalFailure))
                return@flow
            }

            if (rateLimited) {
                val exhaustedProvider = freeAiRouter.detectProvider(platform)
                while (
                    failover is FreeAiFailoverCoordinator.Result.Switched &&
                    freeAiRouter.detectProvider(failover.toPlatform) == exhaustedProvider &&
                    failover.toPlatform.uid !in attemptedPlatformUids
                ) {
                    val skippedSibling = failover.toPlatform
                    attemptedPlatformUids.add(skippedSibling.uid)
                    providerHealthTracker.recordRateLimit(skippedSibling.uid)

                    failover = try {
                        failoverCoordinator.handleFailure(
                            failedPlatformUid = skippedSibling.uid,
                            request = activeRequest.withPlatform(skippedSibling),
                            attemptedPlatformUids = attemptedPlatformUids,
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        FreeAiFailoverCoordinator.Result.NoFallbackAvailable
                    }
                }
            }

            when (failover) {
                is FreeAiFailoverCoordinator.Result.Switched -> {
                    val target = failover.toPlatform
                    if (target.uid in attemptedPlatformUids) {
                        emit(AgentModelEvent.Failed(message = terminalFailure))
                        return@flow
                    }
                    activeRequest = activeRequest.withPlatform(target)
                }

                FreeAiFailoverCoordinator.Result.ManualMode,
                FreeAiFailoverCoordinator.Result.FreeAiDisabled,
                FreeAiFailoverCoordinator.Result.NoFallbackAvailable -> {
                    emit(AgentModelEvent.Failed(message = terminalFailure))
                    return@flow
                }
            }
        }
    }

    private fun resolveRuntimePlatform(platform: PlatformV2): PlatformV2 {
        val isOAuthOpenRouter =
            freeAiRouter.isInternalFree(platform) &&
                freeAiRouter.detectProvider(platform) == FreeAiRouter.Provider.OPENROUTER &&
                platform.token == OpenRouterCredentialStore.PLATFORM_TOKEN_SENTINEL

        if (!isOAuthOpenRouter) return platform

        val oauthToken = openRouterCredentialStore.getApiKey()
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        return platform.copy(token = oauthToken)
    }

    private fun AgentModelRequest.withPlatform(platform: PlatformV2): AgentModelRequest {
        val runtimePlatform = resolveRuntimePlatform(platform)
        return if (this.platform.uid == runtimePlatform.uid && this.platform == runtimePlatform) {
            this
        } else {
            copy(
                platform = runtimePlatform,
                diagnosticContext = diagnosticContext?.copy(
                    platformUid = runtimePlatform.uid,
                ),
            )
        }
    }

    private fun AgentModelRequest.hasImageAttachments(): Boolean =
        fullConversation.any { it.attachments.isNotEmpty() } ||
            conversation.any { it.attachments.isNotEmpty() }

    private fun isOpenRouterPlatform(platform: PlatformV2): Boolean =
        platform.compatibleType == ClientType.OPEN_ROUTER ||
            freeAiRouter.detectProvider(platform) == FreeAiRouter.Provider.OPENROUTER

    private fun isOpenAiCompatible(type: ClientType): Boolean =
        type == ClientType.OPEN_ROUTER ||
            type == ClientType.GOOGLE_AI_STUDIO ||
            type == ClientType.CUSTOM

    private fun String.isRateLimitFailure(): Boolean {
        val normalized = lowercase()
        return "http 429" in normalized ||
            "http_429" in normalized ||
            "status 429" in normalized ||
            "status=429" in normalized ||
            "status: 429" in normalized ||
            "\"code\":429" in normalized ||
            "\"code\": 429" in normalized ||
            "rate limit" in normalized ||
            "rate_limit" in normalized ||
            "too many requests" in normalized ||
            "quota exceeded" in normalized ||
            "resource_exhausted" in normalized
    }

    private fun unsupportedProviderMessage(type: ClientType): String =
        "إعداد المزوّد غير مدعوم حاليًا: ${type.name}."

    companion object {
        private const val INTERACTIVE_PROVIDER_FIRST_OUTPUT_TIMEOUT_MS = 5_000L
        private const val INTERACTIVE_TOTAL_FIRST_OUTPUT_TIMEOUT_MS = 12_000L
    }
}
