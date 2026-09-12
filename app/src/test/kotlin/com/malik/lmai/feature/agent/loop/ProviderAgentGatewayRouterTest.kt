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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderAgentGatewayRouterTest {

    private val gateway = mockk<QwenChatCompletionsAgentGateway>()
    private val responsesGateway = mockk<OpenAiResponsesAgentGateway>()
    private val failover = mockk<FreeAiFailoverCoordinator>()
    private val freeAiRouter = FreeAiRouter()
    private val healthTracker = mockk<ProviderHealthTracker>(relaxed = true)
    private val openRouterCredentialStore = mockk<OpenRouterCredentialStore>(relaxed = true)
    private val mohammedAssistantContext = mockk<HAssistantContext>()

    init {
        every { mohammedAssistantContext.prepare(any()) } answers {
            invocation.args[0] as AgentModelRequest
        }
    }

    private val router = ProviderAgentGatewayRouter(
        gateway,
        responsesGateway,
        failover,
        freeAiRouter,
        healthTracker,
        openRouterCredentialStore,
        mohammedAssistantContext,
    )

    @Test
    fun `smart selected provider is used before stale chat provider`() = runTest {
        val stale = platform("Old Gemini", "external:gemini")
        val active = platform("Hidden Groq", "internal:groq")

        coEvery { failover.resolveStartPlatform(any<AgentModelRequest>()) } returns active
        coEvery { gateway.streamTurn(match { it.platform.uid == active.uid }) } returns
            flowOf(AgentModelEvent.Completed(finalText = "ok"))

        val events = router.streamTurn(request(stale)).toList()

        assertEquals(1, events.size)
        assertTrue(events.single() is AgentModelEvent.Completed)
        coVerify(exactly = 0) { gateway.streamTurn(match { it.platform.uid == stale.uid }) }
        coVerify(exactly = 1) { gateway.streamTurn(match { it.platform.uid == active.uid }) }
        coVerify(exactly = 0) { responsesGateway.streamTurn(any()) }
        verify(exactly = 1) { healthTracker.recordSuccess(active.uid, any()) }
    }

    @Test
    fun `hidden OpenRouter OAuth route resolves encrypted key before gateway call`() = runTest {
        val stale = platform("Old external", "external:custom")
        val openRouter = PlatformV2(
            name = "مساعد H الرقمي · OpenRouter",
            compatibleType = ClientType.OPEN_ROUTER,
            enabled = true,
            apiUrl = "https://openrouter.ai/api/v1",
            token = OpenRouterCredentialStore.PLATFORM_TOKEN_SENTINEL,
            model = "openrouter/free",
            provider = "internal:openrouter",
            isFree = true,
        )

        every { openRouterCredentialStore.getApiKey() } returns "real-oauth-key"
        coEvery { failover.resolveStartPlatform(any<AgentModelRequest>()) } returns openRouter
        coEvery {
            gateway.streamTurn(
                match {
                    it.platform.uid == openRouter.uid &&
                        it.platform.token == "real-oauth-key"
                }
            )
        } returns flowOf(AgentModelEvent.Completed(finalText = "cloud reply"))

        val events = router.streamTurn(request(stale)).toList()

        assertEquals(1, events.size)
        assertTrue(events.single() is AgentModelEvent.Completed)
        verify(exactly = 1) { openRouterCredentialStore.getApiKey() }
        coVerify(exactly = 0) {
            gateway.streamTurn(
                match { it.platform.token == OpenRouterCredentialStore.PLATFORM_TOKEN_SENTINEL }
            )
        }
        coVerify(exactly = 0) { responsesGateway.streamTurn(any()) }
    }

    @Test
    fun `disabled stale external provider is never retried when no route is available`() = runTest {
        val staleExternal = platform("Old external Gemini", "external:gemini")

        coEvery { failover.resolveStartPlatform(any<AgentModelRequest>()) } throws
            IllegalStateException("No active AI provider is available.")

        val events = router.streamTurn(request(staleExternal)).toList()

        assertEquals(1, events.size)
        val failed = events.single() as AgentModelEvent.Failed
        assertTrue(failed.message.contains("No active AI provider"))
        coVerify(exactly = 0) { gateway.streamTurn(any()) }
        coVerify(exactly = 0) { responsesGateway.streamTurn(any()) }
        coVerify(exactly = 0) {
            failover.handleFailure(any(), any(), any())
        }
    }

    @Test
    fun `internal free rate limit switches inside same model turn`() = runTest {
        val primary = platform("Hidden Gemini", "internal:gemini")
        val fallback = platform("Hidden Groq", "internal:groq")

        coEvery { failover.resolveStartPlatform(any<AgentModelRequest>()) } returns primary
        coEvery { gateway.streamTurn(match { it.platform.uid == primary.uid }) } returns
            flowOf(AgentModelEvent.Failed("HTTP 429"))
        coEvery { gateway.streamTurn(match { it.platform.uid == fallback.uid }) } returns
            flowOf(
                AgentModelEvent.OutputDelta("ok"),
                AgentModelEvent.Completed(finalText = "ok"),
            )
        coEvery {
            failover.handleFailure(primary.uid, any(), any())
        } returns FreeAiFailoverCoordinator.Result.Switched(
            fromPlatformUid = primary.uid,
            toPlatform = fallback,
            activatedFreeAi = false,
        )

        val events = router.streamTurn(request(primary)).toList()

        assertEquals(2, events.size)
        assertTrue(events[0] is AgentModelEvent.OutputDelta)
        assertTrue(events[1] is AgentModelEvent.Completed)
        coVerify(exactly = 1) {
            failover.handleFailure(primary.uid, any(), any())
        }
        coVerify(exactly = 1) { gateway.streamTurn(match { it.platform.uid == fallback.uid }) }
        coVerify(exactly = 0) { responsesGateway.streamTurn(any()) }
        verify(exactly = 1) { healthTracker.recordRateLimit(primary.uid) }
        verify(exactly = 0) { healthTracker.recordFailure(primary.uid) }
        verify(exactly = 1) { healthTracker.recordSuccess(fallback.uid, any()) }
    }

    @Test
    fun `internal provider exception before output switches to hidden fallback`() = runTest {
        val primary = platform("Hidden Gemini", "internal:gemini")
        val fallback = platform("Hidden Groq", "internal:groq")

        coEvery { failover.resolveStartPlatform(any<AgentModelRequest>()) } returns primary
        coEvery { gateway.streamTurn(match { it.platform.uid == primary.uid }) } throws
            IllegalStateException("socket closed")
        coEvery { gateway.streamTurn(match { it.platform.uid == fallback.uid }) } returns
            flowOf(AgentModelEvent.Completed(finalText = "recovered"))
        coEvery {
            failover.handleFailure(primary.uid, any(), any())
        } returns FreeAiFailoverCoordinator.Result.Switched(
            fromPlatformUid = primary.uid,
            toPlatform = fallback,
            activatedFreeAi = false,
        )

        val events = router.streamTurn(request(primary)).toList()

        assertEquals(1, events.size)
        assertTrue(events.single() is AgentModelEvent.Completed)
        coVerify(exactly = 1) {
            failover.handleFailure(primary.uid, any(), any())
        }
        coVerify(exactly = 0) { responsesGateway.streamTurn(any()) }
        verify(exactly = 1) { healthTracker.recordFailure(primary.uid) }
    }

    @Test
    fun `paid external failure surfaces without free fallback`() = runTest {
        val paid = platform("Paid private API", "external:custom")

        coEvery { failover.resolveStartPlatform(any<AgentModelRequest>()) } returns paid
        coEvery { gateway.streamTurn(match { it.platform.uid == paid.uid }) } returns
            flowOf(AgentModelEvent.Failed("paid provider unavailable"))
        coEvery {
            failover.handleFailure(paid.uid, any(), any())
        } returns FreeAiFailoverCoordinator.Result.NoFallbackAvailable

        val events = router.streamTurn(request(paid)).toList()

        assertEquals(1, events.size)
        val failed = events.single() as AgentModelEvent.Failed
        assertEquals("paid provider unavailable", failed.message)
        coVerify(exactly = 1) { gateway.streamTurn(match { it.platform.uid == paid.uid }) }
        coVerify(exactly = 1) { failover.handleFailure(paid.uid, any(), any()) }
        coVerify(exactly = 0) { responsesGateway.streamTurn(any()) }
    }

    @Test
    fun `partial output failure is surfaced without switching providers`() = runTest {
        val primary = platform("Primary", "internal:gemini")

        coEvery { failover.resolveStartPlatform(any<AgentModelRequest>()) } returns primary
        coEvery { gateway.streamTurn(any()) } returns
            flowOf(
                AgentModelEvent.OutputDelta("partial"),
                AgentModelEvent.Failed("connection lost"),
            )

        val events = router.streamTurn(request(primary)).toList()

        assertEquals(2, events.size)
        assertTrue(events[0] is AgentModelEvent.OutputDelta)
        assertTrue(events[1] is AgentModelEvent.Failed)
        coVerify(exactly = 0) {
            failover.handleFailure(any(), any(), any())
        }
        coVerify(exactly = 0) { responsesGateway.streamTurn(any()) }
        verify(exactly = 1) { healthTracker.recordFailure(primary.uid) }
    }

    @Test
    fun `no fallback surfaces original provider failure`() = runTest {
        val primary = platform("Hidden Gemini", "internal:gemini")

        coEvery { failover.resolveStartPlatform(any<AgentModelRequest>()) } returns primary
        coEvery { gateway.streamTurn(any()) } returns
            flowOf(AgentModelEvent.Failed("provider unavailable"))
        coEvery {
            failover.handleFailure(primary.uid, any(), any())
        } returns FreeAiFailoverCoordinator.Result.NoFallbackAvailable

        val events = router.streamTurn(request(primary)).toList()

        assertEquals(1, events.size)
        val failed = events.single() as AgentModelEvent.Failed
        assertEquals("provider unavailable", failed.message)
        coVerify(exactly = 0) { responsesGateway.streamTurn(any()) }
    }

    private fun request(platform: PlatformV2) = AgentModelRequest(
        platform = platform,
        conversation = emptyList(),
        fullConversation = emptyList(),
        tools = emptyList(),
        policy = AgentLoopPolicy(),
    )

    private fun platform(
        name: String,
        provider: String,
        token: String? = "key",
    ) = PlatformV2(
        name = name,
        compatibleType = ClientType.CUSTOM,
        apiUrl = "https://example.test/v1",
        token = token,
        model = "model",
        provider = provider,
        isFree = provider.startsWith("internal:"),
    )
}
