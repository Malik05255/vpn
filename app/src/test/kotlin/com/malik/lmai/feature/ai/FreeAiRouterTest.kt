package com.malik.lmai.feature.ai

import com.malik.lmai.data.database.entity.PlatformV2
import com.malik.lmai.data.model.ClientType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FreeAiRouterTest {

    private val router = FreeAiRouter()

    @Test
    fun `orders credentialless BlockRun before credentialed cloud providers`() {
        val platforms = listOf(
            platform(name = "OpenRouter Free", provider = "internal:openrouter", token = "or-key", isFree = true),
            platform(name = "Groq Free", provider = "internal:groq", token = "groq-key", isFree = true),
            platform(name = "Gemini Free", provider = "internal:gemini", token = "gem-key", isFree = true),
            blockRunPlatform(),
        )

        val result = router.orderedCandidates(platforms)

        assertEquals(
            listOf(
                FreeAiRouter.Provider.BLOCKRUN,
                FreeAiRouter.Provider.OPENROUTER,
                FreeAiRouter.Provider.GEMINI,
                FreeAiRouter.Provider.GROQ,
            ),
            result.map { it.provider },
        )
    }

    @Test
    fun `BlockRun internal route is valid without any key`() {
        val blockRun = blockRunPlatform()

        assertEquals(FreeAiRouter.Provider.BLOCKRUN, router.detectProvider(blockRun))
        assertTrue(router.isInternalFree(blockRun))
        assertTrue(router.isFreeCandidate(blockRun))
    }

    @Test
    fun `BlockRun display name cannot whitelist an arbitrary no-key endpoint`() {
        val spoofed = PlatformV2(
            name = "BlockRun Free",
            compatibleType = ClientType.CUSTOM,
            apiUrl = "https://example.test/api",
            token = null,
            model = "model",
            provider = "internal:blockrun",
            isFree = true,
        )

        assertEquals(FreeAiRouter.Provider.BLOCKRUN, router.detectProvider(spoofed))
        assertFalse(router.isFreeCandidate(spoofed))
    }

    @Test
    fun `legacy local route is unknown and never selected`() {
        val local = platform(
            name = "Legacy Local",
            provider = "internal:local",
            token = null,
            isFree = true,
        )

        assertEquals(FreeAiRouter.Provider.UNKNOWN, router.detectProvider(local))
        assertTrue(router.isInternalFree(local))
        assertFalse(router.isFreeCandidate(local))
        assertTrue(router.orderedCandidates(listOf(local)).isEmpty())
    }

    @Test
    fun `nextAfter advances only through internal cloud free providers`() {
        val blockRun = blockRunPlatform()
        val openRouter = platform(name = "OpenRouter", provider = "internal:openrouter", token = "o", isFree = true)
        val gemini = platform(name = "Gemini", provider = "internal:gemini", token = "g", isFree = true)

        assertEquals(openRouter.uid, router.nextAfter(listOf(gemini, openRouter, blockRun), blockRun.uid)?.uid)
        assertEquals(gemini.uid, router.nextAfter(listOf(gemini, openRouter, blockRun), openRouter.uid)?.uid)
        assertNull(router.nextAfter(listOf(blockRun, openRouter, gemini), gemini.uid))
    }

    @Test
    fun `external and internal provider from same vendor never collide`() {
        val externalGemini = PlatformV2(
            name = "My Google AI Studio",
            compatibleType = ClientType.GOOGLE_AI_STUDIO,
            enabled = true,
            apiUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
            token = "user-key",
            model = "gemini-user-model",
            provider = "external:gemini",
            isFree = true,
        )
        val internalGemini = platform(
            name = "Hidden Gemini",
            provider = "internal:gemini",
            token = "internal-key",
            isFree = true,
        )

        assertEquals(FreeAiRouter.Provider.GEMINI, router.detectProvider(externalGemini))
        assertEquals(FreeAiRouter.Provider.GEMINI, router.detectProvider(internalGemini))
        assertTrue(router.isExternal(externalGemini))
        assertFalse(router.isFreeCandidate(externalGemini))
        assertTrue(router.isInternalFree(internalGemini))
        assertTrue(router.isFreeCandidate(internalGemini))
    }

    @Test
    fun `legacy google ai studio free tier remains external`() {
        val legacyExternalGemini = PlatformV2(
            name = "Google AI Studio",
            compatibleType = ClientType.GOOGLE_AI_STUDIO,
            apiUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
            token = "user-key",
            model = "gemini-model",
            provider = "gemini",
            isFree = true,
        )

        assertTrue(router.isExternal(legacyExternalGemini))
        assertFalse(router.isFreeCandidate(legacyExternalGemini))
    }

    @Test
    fun `unknown external platform is not a free candidate`() {
        val paid = platform(
            name = "Private endpoint",
            provider = "external:custom",
            token = "private-key",
            isFree = false,
        )

        assertEquals(emptyList<FreeAiRouter.Candidate>(), router.orderedCandidates(listOf(paid)))
    }

    @Test
    fun `explicit provider code wins over misleading display name and url`() {
        val explicitGroq = PlatformV2(
            name = "Gemini-looking custom name",
            compatibleType = ClientType.CUSTOM,
            apiUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
            token = "key",
            model = "model",
            provider = "external:groq",
            isFree = true,
        )

        assertEquals(
            FreeAiRouter.Provider.GROQ,
            router.detectProvider(explicitGroq),
        )
        assertFalse(router.isFreeCandidate(explicitGroq))
    }

    private fun blockRunPlatform() = PlatformV2(
        name = "Free AI · Code",
        compatibleType = ClientType.CUSTOM,
        apiUrl = FreeAiRouter.BLOCKRUN_API_BASE,
        token = null,
        model = FreeAiBootstrapper.BLOCKRUN_CODE_MODEL,
        provider = "internal:blockrun",
        isFree = true,
    )

    private fun platform(
        name: String,
        provider: String,
        token: String?,
        isFree: Boolean,
    ) = PlatformV2(
        name = name,
        compatibleType = ClientType.CUSTOM,
        apiUrl = if (provider == "internal:local") "local://legacy" else "https://example.test/v1",
        token = token,
        model = "test-model",
        provider = provider,
        isFree = isFree,
    )
}
