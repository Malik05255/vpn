package com.malik.lmai.feature.agent.loop

import com.malik.lmai.data.database.entity.PlatformV2
import com.malik.lmai.data.model.ClientType
import com.malik.lmai.feature.agent.AgentLoopPolicy
import com.malik.lmai.feature.agent.AgentModelEvent
import com.malik.lmai.feature.agent.AgentModelRequest
import com.malik.lmai.feature.ai.FreeAiFailoverCoordinator
import com.malik.lmai.feature.ai.FreeAiRouter
import com.malik.lmai.feature.ai.ProviderHealthTracker
import com.malik.lmai.feature.ai.openrouter.OpenRouterCredentialStore
import com.malik.lmai.feature.assistant.HAssistantContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderAgentGatewayRouterResilienceTest {

    private val gateway = mockk<QwenChatCompletionsAgentGateway>()
    private val responsesGateway = mockk<OpenAiResponsesAgentGateway>()
    private val failover = mockk<FreeAiFailoverCoordinator>()
    private val freeAiRouter = FreeAiRouter()
    private val healthTracker = mockk<ProviderHealthTracker>(relaxed = true)
    private val credentialStore = mockk<OpenRouterCredentialStore>(relaxed = true)
    private val assistantContext = mockk<HAssistantContext>()

    init {
        every { assistantContext.prepare(any()) } answers {
            invocation.args[0] as AgentModelRequest
        }
    }

    private val router = ProviderAgentGatewayRouter(
        gateway,
        responsesGateway,
        failover,
        freeAiRouter,
        healthTracker,
        credentialStore,
        assistantContext,
    )

    @Test
    fun `hidden thinking followed by rate limit switches provider`() = runTest {
        val primary = internalPlatform("Primary BlockRun", "internal:blockrun")
        val fallback = internalPlatform("Fallback Gemini", "internal:gemini")

        coEvery { failover.resolveStartPlatform(any<AgentModelRequest>()) } returns primary
        coEvery { gateway.streamTurn(match { it.platform.uid == primary.uid }) } returns
            flowOf(
                AgentModelEvent.ThinkingDelta("hidden reasoning"),
                AgentModelEvent.Failed("HTTP 429 rate limit exceeded"),
            )
        coEvery { gateway.streamTurn(match { it.platform.uid == fallback.uid }) } returns
            flowOf(
                AgentModelEvent.OutputDelta("recovered"),
                AgentModelEvent.Completed(finalText = "recovered"),
            )
        coEvery {
            failover.handleFailure(primary.uid, any(), any())
        } returns FreeAiFailoverCoordinator.Result.Switched(
            fromPlatformUid = primary.uid,
            toPlatform = fallback,
            activatedFreeAi = false,
        )

        val events = router.streamTurn(request(primary)).toList()

        assertTrue(events.any { it is AgentModelEvent.OutputDelta && it.delta == "recovered" })
        assertTrue(events.last() is AgentModelEvent.Completed)
        verify(exactly = 1) { healthTracker.recordRateLimit(primary.uid) }
        verify(exactly = 0) { healthTracker.recordFailure(primary.uid) }
        coVerify(exactly = 1) { gateway.streamTurn(match { it.platform.uid == fallback.uid }) }
    }

    @Test
    fun `slow internal chat route is abandoned before long empty wait`() = runTest {
        val primary = internalPlatform("Slow BlockRun", "internal:blockrun")
        val fallback = internalPlatform("Fast Gemini", "internal:gemini")

        coEvery { failover.resolveStartPlatform(any<AgentModelRequest>()) } returns primary
        coEvery { gateway.streamTurn(match { it.platform.uid == primary.uid }) } returns
            flow {
                delay(6_000L)
                emit(AgentModelEvent.OutputDelta("late"))
                emit(AgentModelEvent.Completed(finalText = "late"))
            }
        coEvery { gateway.streamTurn(match { it.platform.uid == fallback.uid }) } returns
            flowOf(
                AgentModelEvent.OutputDelta("fast"),
                AgentModelEvent.Completed(finalText = "fast"),
            )
        coEvery {
            failover.handleFailure(primary.uid, any(), any())
        } returns FreeAiFailoverCoordinator.Result.Switched(
            fromPlatformUid = primary.uid,
            toPlatform = fallback,
            activatedFreeAi = false,
        )

        val events = router.streamTurn(request(primary)).toList()

        assertEquals("fast", (events.first { it is AgentModelEvent.OutputDelta } as AgentModelEvent.OutputDelta).delta)
        assertTrue(events.last() is AgentModelEvent.Completed)
        verify(exactly = 1) { healthTracker.recordFailure(primary.uid) }
        coVerify(exactly = 1) { gateway.streamTurn(match { it.platform.uid == fallback.uid }) }
    }

    private fun request(platform: PlatformV2) = AgentModelRequest(
        platform = platform,
        conversation = emptyList(),
        fullConversation = emptyList(),
        tools = emptyList(),
        policy = AgentLoopPolicy(),
    )

    private fun internalPlatform(
        name: String,
        provider: String,
    ) = PlatformV2(
        name = name,
        compatibleType = ClientType.CUSTOM,
        apiUrl = if (provider == "internal:blockrun") {
            FreeAiRouter.BLOCKRUN_API_BASE
        } else {
            "https://example.test/v1"
        },
        token = if (provider == "internal:blockrun") null else "internal-key",
        model = "test-model",
        provider = provider,
        isFree = true,
    )
}
