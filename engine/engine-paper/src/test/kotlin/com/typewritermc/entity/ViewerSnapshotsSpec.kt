package com.typewritermc.entity

import com.typewritermc.engine.paper.entry.entity.ViewerSnapshots
import com.typewritermc.engine.paper.entry.dialogue.currentDialogue
import com.typewritermc.engine.paper.entry.dialogue.speakersInDialogue
import com.typewritermc.engine.paper.utils.FoliaScheduler
import com.typewritermc.engine.paper.utils.foliaScheduler
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.*
import org.bukkit.entity.Player
import org.bukkit.Location
import org.bukkit.World
import java.util.UUID

class ViewerSnapshotsSpec : FunSpec({
    val playerId = UUID.randomUUID()
    val player = mockk<Player>()
    val scheduler = mockk<FoliaScheduler>()
    val world = mockk<World>()

    beforeSpec {
        mockkStatic("com.typewritermc.engine.paper.utils.PaperCoroutineKt")
        mockkStatic("com.typewritermc.engine.paper.entry.dialogue.DialogueInteractionKt")
        every { foliaScheduler } returns scheduler
        every { scheduler.owns(player) } returns true
        every { player.uniqueId } returns playerId
        every { world.uid } returns UUID.randomUUID()
        every { player.location } returns Location(world, 12.0, 64.0, 3.0)
        every { player.eyeLocation } returns Location(world, 12.0, 65.6, 3.0)
        every { player.isValid } returns false
        every { player.currentDialogue } returns null
        every { player.speakersInDialogue } returns emptySet()
    }
    beforeTest {
        ViewerSnapshots.clear()
        clearMocks(player, answers = false)
    }
    afterSpec {
        ViewerSnapshots.clear()
        unmockkStatic("com.typewritermc.engine.paper.utils.PaperCoroutineKt")
        unmockkStatic("com.typewritermc.engine.paper.entry.dialogue.DialogueInteractionKt")
    }

    test("a stopped session cannot sample or republish its player") {
        val lease = ViewerSnapshots.open(playerId)
        ViewerSnapshots.remove(playerId, lease)
        ViewerSnapshots.capture(player, lease)
        ViewerSnapshots[playerId] shouldBe null
        verify(exactly = 0) { player.location }
        verify(exactly = 0) { player.eyeLocation }
    }

    test("an old session cannot remove or sample a replacement session with the same UUID") {
        val previous = ViewerSnapshots.open(playerId)
        val current = ViewerSnapshots.open(playerId)
        ViewerSnapshots.remove(playerId, previous)
        ViewerSnapshots.capture(player, previous)
        verify(exactly = 0) { player.location }
        ViewerSnapshots.capture(player, current)
        ViewerSnapshots[playerId]?.position?.x shouldBe 12.0
        verify(exactly = 1) { player.location }
    }

    test("clearing a generation invalidates every publisher") {
        val lease = ViewerSnapshots.open(playerId)
        ViewerSnapshots.clear()
        ViewerSnapshots.capture(player, lease)
        ViewerSnapshots[playerId] shouldBe null
        verify(exactly = 0) { player.eyeLocation }
    }
})
