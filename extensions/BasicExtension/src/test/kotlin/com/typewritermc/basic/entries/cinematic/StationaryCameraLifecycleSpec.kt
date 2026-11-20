package com.typewritermc.basic.entries.cinematic

import com.typewritermc.engine.paper.logger
import com.typewritermc.engine.paper.utils.entityDispatcher
import com.typewritermc.engine.paper.utils.isFloodgate
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import org.bukkit.entity.Player
import java.util.logging.Logger

class StationaryCameraLifecycleSpec : FunSpec({
    val player = mockk<Player>()
    val log = Logger.getLogger("StationaryCameraLifecycleTest")
    val entry = StationaryCameraCinematicEntry(
        segments = listOf(StationaryCameraSegment()), unavailableMessage = "",
    )

    beforeTest {
        clearMocks(player)
        mockkStatic("com.typewritermc.engine.paper.utils.PaperCoroutineKt")
        mockkStatic("com.typewritermc.engine.paper.utils.GeyserExtensionsKt")
        mockkStatic("com.typewritermc.engine.paper.TypewriterPaperPluginKt")
        every { player.entityDispatcher } returns Dispatchers.Unconfined
        every { player.isFloodgate } returns true
        every { player.isOnline } returns true
        every { logger } returns log
    }

    afterTest {
        unmockkStatic("com.typewritermc.engine.paper.utils.PaperCoroutineKt")
        unmockkStatic("com.typewritermc.engine.paper.utils.GeyserExtensionsKt")
        unmockkStatic("com.typewritermc.engine.paper.TypewriterPaperPluginKt")
    }

    test("a rejected setup reaches TemporalInteraction's stop branch on its first tick") {
        val action = StationaryCameraCinematicAction(player, entry)
        action.setup()
        action.canFinish(0) shouldBe false
        action.tick(0)
        action.canFinish(0) shouldBe true
        action.teardown()
        action.retire()
        verify(exactly = 0) { player.location }
    }

    test("retirement before queued setup prevents acquisition and any player state access") {
        val action = StationaryCameraCinematicAction(player, entry)
        action.retire()
        action.setup()
        action.tick(0)
        action.canFinish(0) shouldBe true
        action.teardown()
        verify(exactly = 0) { player.isFloodgate }
        verify(exactly = 0) { player.isOnline }
        verify(exactly = 0) { player.location }
        verify(exactly = 0) { player.inventory }
    }
})
