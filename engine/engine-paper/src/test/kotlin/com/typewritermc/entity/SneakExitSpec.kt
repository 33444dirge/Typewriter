package com.typewritermc.entity

import com.typewritermc.core.interaction.Interaction
import com.typewritermc.core.interaction.InteractionScope
import com.typewritermc.engine.paper.interaction.ConfirmationRegistration
import com.typewritermc.engine.paper.interaction.PlayerSession
import com.typewritermc.engine.paper.interaction.SneakExitControl
import com.typewritermc.engine.paper.entry.entries.Event
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.*
import java.util.UUID
import org.bukkit.entity.Player

class SneakExitSpec : FunSpec({
    test("exit is one-shot and a stale input cannot exit the next interaction") {
        val presses = mutableListOf<() -> Unit>()
        var disposed = 0
        val control = SneakExitControl<Any> { press ->
            presses += press
            ConfirmationRegistration { disposed++ }
        }
        val first = Any()
        val next = Any()
        control.update(first)
        presses[0]()
        control.consume(first) shouldBe true
        control.consume(first) shouldBe false
        control.update(next)
        presses[0]()
        control.consume(next) shouldBe false
        presses[1]()
        control.consume(next) shouldBe true
        control.close()
        control.close()
        presses[1]()
        control.consume(next) shouldBe false
        disposed shouldBe 2
    }

    test("idle and retired sessions release their registrations and pending targets") {
        lateinit var press: () -> Unit
        var disposed = 0
        var registrations = 0
        val control = SneakExitControl<Any> {
            registrations++
            press = it
            ConfirmationRegistration { disposed++ }
        }
        val target = Any()
        control.update(target)
        control.update(target)
        registrations shouldBe 1
        press()
        control.update(null)
        control.consume(target) shouldBe false
        disposed shouldBe 1
        press()
        control.consume(target) shouldBe false
        control.close()
        control.update(target)
        registrations shouldBe 1
    }

    test("pending input cannot cancel an unrelated interaction") {
        lateinit var press: () -> Unit
        val control = SneakExitControl<Any> {
            press = it
            ConfirmationRegistration {}
        }
        control.update(Any())
        press()
        control.consume(Any()) shouldBe false
        control.close()
    }

    test("session exit discards already queued continuation and tears down its scope exactly once") {
        val player = mockk<Player>()
        every { player.uniqueId } returns UUID.randomUUID()
        val session = PlayerSession(player)
        val interaction = mockk<Interaction>()
        val scope = mockk<InteractionScope>()
        every { scope.interaction } returns interaction
        coEvery { scope.teardown() } just Runs
        session.scope = scope
        lateinit var press: () -> Unit
        var released = 0
        val control = SneakExitControl<Interaction> {
            press = it
            ConfirmationRegistration { released++ }
        }
        PlayerSession::class.java.getDeclaredField("sneakExit").apply { isAccessible = true }.set(session, control)
        control.update(interaction)
        val queued = mockk<Event>()
        session.addToSchedule(queued)
        press()
        session.forceEvent(mockk())
        session.interaction shouldBe null
        coVerify(exactly = 1) { scope.teardown() }
        verify { queued wasNot Called }
        released shouldBe 1
        session.forceEvent(mockk<Event>().also { every { it.triggers } returns emptyList() })
        coVerify(exactly = 1) { scope.teardown() }
        control.close()
    }
})
