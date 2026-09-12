package com.malik.lmai.feature.ai

import com.malik.lmai.data.database.entity.PlatformV2
import com.malik.lmai.data.model.ClientType
import com.malik.lmai.data.repository.SettingRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FreeAiBootstrapperTest {

    private val repository = mockk<SettingRepository>(relaxed = true)
    private val router = FreeAiRouter()
    private val bootstrapper = FreeAiBootstrapper(repository, router)

    @Test
    fun `fresh install provisions cloud routes without choosing a runtime provider`() = runTest {
        var platforms = emptyList<PlatformV2>()
        wireMutableRepository { platforms } { platforms = it }

        val result = bootstrapper.ensureReady()

        assertEquals(4, result.size)
        val blockRunRoutes = result.filter {
            router.detectProvider(it) == FreeAiRouter.Provider.BLOCKRUN
        }
        assertEquals(3, blockRunRoutes.size)
        assertTrue(blockRunRoutes.none { it.enabled })
        assertTrue(blockRunRoutes.all { it.token == null })
        assertTrue(blockRunRoutes.all(router::isInternalFree))

        val openRouterRoutes = result.filter {
            router.detectProvider(it) == FreeAiRouter.Provider.OPENROUTER
        }
        assertEquals(1, openRouterRoutes.size)
        assertFalse(openRouterRoutes.single().enabled)
        assertTrue(router.isInternalFree(openRouterRoutes.single()))

        coVerify(exactly = 4) { repository.addPlatformV2(any()) }
        coVerify(exactly = 0) { repository.updateFreeAiEnabled(any()) }
    }

    @Test
    fun `legacy local route is deleted and never recreated`() = runTest {
        val local = legacyLocalPlatform()
        val openRouter = openRouterPlatform(enabled = false)
        var platforms = listOf(local, openRouter)
        wireMutableRepository { platforms } { platforms = it }

        val result = bootstrapper.ensureReady()

        assertFalse(result.any { it.uid == local.uid })
        assertFalse(result.any { platform ->
            platform.provider.orEmpty().contains("local", ignoreCase = true) ||
                platform.apiUrl.startsWith("local://", ignoreCase = true)
        })
        assertEquals(3, result.count { router.detectProvider(it) == FreeAiRouter.Provider.BLOCKRUN })
        assertEquals(1, result.count { router.detectProvider(it) == FreeAiRouter.Provider.OPENROUTER })

        coVerify(exactly = 1) { repository.deletePlatformV2(match { it.uid == local.uid }) }
        coVerify(exactly = 3) { repository.addPlatformV2(any()) }
        coVerify(exactly = 0) { repository.updateFreeAiEnabled(any()) }
    }

    @Test
    fun `bootstrap never rewrites provider selection when external API is active`() = runTest {
        val external = PlatformV2(
            name = "My API",
            compatibleType = ClientType.CUSTOM,
            enabled = true,
            apiUrl = "https://example.test/v1",
            token = "user-key",
            model = "model",
            provider = "external:custom",
            isFree = false,
        )
        val openRouter = openRouterPlatform(enabled = true)
        var platforms = listOf(external, openRouter)
        wireMutableRepository { platforms } { platforms = it }

        val result = bootstrapper.ensureReady()

        assertTrue(result.first { it.uid == external.uid }.enabled)
        assertTrue(result.first { it.uid == openRouter.uid }.enabled)
        coVerify(exactly = 0) { repository.updateFreeAiEnabled(any()) }
        coVerify(exactly = 0) {
            repository.updatePlatformV2(match { it.uid == openRouter.uid && !it.enabled })
        }
    }

    private fun wireMutableRepository(
        read: () -> List<PlatformV2>,
        write: (List<PlatformV2>) -> Unit,
    ) {
        coEvery { repository.fetchPlatformV2s() } answers { read() }
        coEvery { repository.addPlatformV2(any()) } answers {
            val added = invocation.args[0] as PlatformV2
            write(read() + added)
        }
        coEvery { repository.updatePlatformV2(any()) } answers {
            val updated = invocation.args[0] as PlatformV2
            write(read().map { current ->
                if (current.uid == updated.uid) updated else current
            })
        }
        coEvery { repository.deletePlatformV2(any()) } answers {
            val deleted = invocation.args[0] as PlatformV2
            write(read().filterNot { current -> current.uid == deleted.uid })
        }
    }

    private fun legacyLocalPlatform() = PlatformV2(
        name = "مساعد H الرقمي · محلي",
        compatibleType = ClientType.CUSTOM,
        enabled = true,
        apiUrl = "local://android-aicore",
        token = null,
        model = "qwen2.5-0.5b-instruct-q8_0.task",
        provider = "internal:local",
        isFree = true,
    )

    private fun openRouterPlatform(enabled: Boolean) = PlatformV2(
        name = FreeAiBootstrapper.H_OPENROUTER_DISPLAY_NAME,
        compatibleType = ClientType.OPEN_ROUTER,
        enabled = enabled,
        apiUrl = "https://openrouter.ai/api/v1",
        token = "oauth://openrouter",
        model = "openrouter/free",
        provider = "internal:openrouter",
        isFree = true,
    )
}
