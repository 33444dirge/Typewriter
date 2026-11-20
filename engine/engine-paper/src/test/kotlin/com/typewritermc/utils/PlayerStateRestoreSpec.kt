package com.typewritermc.utils

import com.typewritermc.engine.paper.utils.*
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.*
import kotlinx.coroutines.*
import org.bukkit.Location
import org.bukkit.entity.Player
import java.util.concurrent.CompletableFuture

class PlayerStateRestoreSpec : FunSpec({
    val player = mockk<Player>()
    val scheduler = mockk<FoliaScheduler>()
    val destination = mockk<Location>()
    val provider = mockk<PlayerStateProvider>()
    val saved = Any()
    val state = PlayerState(linkedMapOf(GenericPlayerStateProvider.LOCATION to destination, provider to saved))

    beforeTest {
        clearMocks(provider)
        mockkStatic("com.typewritermc.engine.paper.utils.PaperCoroutineKt")
        every { foliaScheduler } returns scheduler
        every { player.entityDispatcher } returns Dispatchers.Unconfined
        every { provider.restore(player, saved) } just Runs
    }

    afterTest { unmockkStatic("com.typewritermc.engine.paper.utils.PaperCoroutineKt") }

    test("a rejected teleport still restores the temporary non-location state") {
        every { scheduler.teleport(player, destination) } returns CompletableFuture.completedFuture(false)
        player.restoreAsync(state) shouldBe false
        verify(exactly = 1) { provider.restore(player, saved) }
    }

    test("a failed teleport restores other state before preserving the original failure") {
        val failure = IllegalStateException("destination unavailable")
        every { scheduler.teleport(player, destination) } returns CompletableFuture.failedFuture(failure)
        shouldThrow<IllegalStateException> { player.restoreAsync(state) } shouldBe failure
        verify(exactly = 1) { provider.restore(player, saved) }
    }

    test("retiring the owning job does not force player writes from a cancelled continuation") {
        val movement = CompletableFuture<Boolean>()
        every { scheduler.teleport(player, destination) } returns movement
        val restoring = CoroutineScope(Dispatchers.Unconfined).launch(start = CoroutineStart.UNDISPATCHED) {
            player.restoreAsync(state)
        }
        restoring.cancelAndJoin()
        verify(exactly = 0) { provider.restore(player, saved) }
        movement.isCancelled shouldBe true
    }
})
