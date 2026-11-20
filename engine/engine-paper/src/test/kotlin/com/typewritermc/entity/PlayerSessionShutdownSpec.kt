package com.typewritermc.entity

import com.typewritermc.engine.paper.interaction.PlayerSession
import com.typewritermc.engine.paper.interaction.PlayerSessionManager
import com.typewritermc.engine.paper.utils.entityDispatcher
import com.typewritermc.engine.paper.utils.foliaScheduler
import com.typewritermc.engine.paper.utils.FoliaScheduler
import com.typewritermc.core.interaction.Interaction
import com.typewritermc.core.interaction.InteractionContext
import com.typewritermc.core.interaction.InteractionScope
import com.typewritermc.core.interaction.RetirableInteraction
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.*
import java.time.Duration
import org.bukkit.entity.Player
import java.util.UUID

class PlayerSessionShutdownSpec : FunSpec({
    beforeTest {
        mockkStatic("com.typewritermc.engine.paper.utils.PaperCoroutineKt")
        val scheduler = mockk<FoliaScheduler>()
        every { foliaScheduler } returns scheduler
        every { scheduler.active() } returns true
    }
    afterTest { unmockkStatic("com.typewritermc.engine.paper.utils.PaperCoroutineKt") }

    test("disconnect releases connection resources before waiting for a suspended ticker") {
        val player = mockk<Player>()
        every { player.uniqueId } returns UUID.randomUUID()
        val session = PlayerSession(player)
        var retired = 0
        val interaction = object : Interaction, RetirableInteraction {
            override val context = mockk<InteractionContext>()
            override val priority = 0
            override suspend fun initialize() = Result.success(Unit)
            override suspend fun tick(deltaTime: Duration) {}
            override suspend fun teardown() {}
            override fun retire() { retired++ }
        }
        session.scope = mockk<InteractionScope>().also {
            every { it.interaction } returns interaction
            every { it.bound } returns com.typewritermc.core.interaction.InteractionBound.Empty
        }
        val release = CompletableDeferred<Unit>()
        val ticker = CoroutineScope(Dispatchers.Unconfined).launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { release.await() }
            }
        }
        PlayerSession::class.java.getDeclaredField("sessionTickingJob").apply { isAccessible = true }.set(session, ticker)
        try {
            session.retire()
            retired shouldBe 1
            ticker.isCancelled shouldBe true
            ticker.isCompleted shouldBe false
            session.retire()
            retired shouldBe 1
        } finally {
            release.complete(Unit)
            ticker.join()
        }
    }

    test("one failed session teardown still closes every other session and clears the active table") {
        val manager = PlayerSessionManager()
        val players = List(3) {
            mockk<Player>().also { player ->
                every { player.uniqueId } returns UUID.randomUUID()
                every { player.entityDispatcher } returns Dispatchers.Unconfined
            }
        }
        val failure = IllegalStateException("first session failed")
        val sessions = players.mapIndexed { index, player ->
            mockk<PlayerSession>().also { session ->
                every { session.player } returns player
                coEvery { session.teardown() } coAnswers { if (index == 0) throw failure }
            }
        }
        val field = PlayerSessionManager::class.java.getDeclaredField("sessions").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val table = field.get(manager) as MutableMap<UUID, PlayerSession>
        players.zip(sessions).forEach { (player, session) -> table[player.uniqueId] = session }

        val error = shouldThrow<IllegalStateException> { manager.unload() }
        error.suppressed.toList() shouldBe listOf(failure)
        sessions.forEach { session -> coVerify(exactly = 1) { session.teardown() } }
        table.size shouldBe 0
        manager.shutdown()
        sessions.forEach { session -> coVerify(exactly = 1) { session.teardown() } }
    }

    test("stopped scheduling retires sessions without submitting another entity callback") {
        every { foliaScheduler.active() } returns false
        val manager = PlayerSessionManager()
        val session = mockk<PlayerSession>()
        coEvery { session.releaseRetiredResources() } just Runs
        val field = PlayerSessionManager::class.java.getDeclaredField("sessions").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val table = field.get(manager) as MutableMap<UUID, PlayerSession>
        table[UUID.randomUUID()] = session

        manager.shutdown()

        coVerify(exactly = 1) { session.releaseRetiredResources() }
        coVerify(exactly = 0) { session.teardown() }
        verify(exactly = 0) { session.player }
        table.size shouldBe 0
    }
})
