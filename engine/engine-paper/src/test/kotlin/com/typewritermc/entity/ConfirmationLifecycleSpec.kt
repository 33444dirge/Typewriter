package com.typewritermc.entity

import com.typewritermc.engine.paper.interaction.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.entity.Player
import java.util.UUID

class ConfirmationLifecycleSpec : FunSpec({
    test("clearing a disconnected player disposes input once and ignores stale presses") {
        val id = UUID.randomUUID()
        val player = mockk<Player>()
        every { player.uniqueId } returns id
        val confirmations = Confirmations()
        var press: () -> Unit = {}
        var releases = 0
        var confirmed = 0
        val input = object : ConfirmationInput {
            override val available = listOf(ConfirmationKey.SNEAK)
            override fun listen(key: ConfirmationKey, onPress: () -> Unit): ConfirmationRegistration {
                press = onPress
                return ConfirmationRegistration { releases++ }
            }
        }
        val registration = confirmations.takeOver(player, input)
        val confirmation = confirmations.await(player, ConfirmationKey.SNEAK) { confirmed++ }
        press()
        confirmed shouldBe 1
        confirmations.clear(id)
        confirmations.clear(id)
        registration.dispose()
        confirmation.dispose()
        press()
        confirmed shouldBe 1
        releases shouldBe 1
        confirmations.keyOffered(player) shouldBe null
    }
})
