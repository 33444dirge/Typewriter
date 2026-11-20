package com.typewritermc.entity

import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketType.Play
import com.github.retrooper.packetevents.protocol.player.User
import com.typewritermc.engine.paper.interaction.ActionBarBlockerHandler
import com.typewritermc.engine.paper.interaction.ChatHistoryHandler
import com.typewritermc.engine.paper.interaction.ResendTokenRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.Component
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PacketLoginLifecycleSpec : FunSpec({
    test("login packets with no player UUID do not enter action bar or chat processing") {
        val user = mockk<User>()
        every { user.uuid } returns null
        val event = mockk<PacketSendEvent>()
        every { event.user } returns user
        every { event.packetType } returns Play.Server.SYSTEM_CHAT_MESSAGE

        ActionBarBlockerHandler().onPacketSend(event)
        ChatHistoryHandler().onPacketSend(event)

        verify(exactly = 0) { event.byteBuf }
        verify(exactly = 0) { event.isCancelled = any() }
    }

    test("an absent history is not created by a late network callback") {
        val user = mockk<User>()
        every { user.uuid } returns UUID.randomUUID()
        val event = mockk<PacketSendEvent>()
        every { event.user } returns user

        ChatHistoryHandler().onPacketSend(event)

        verify(exactly = 0) { event.packetType }
    }

    test("concurrent resends consume each issued token exactly once") {
        val registry = ResendTokenRegistry()
        val playerId = UUID.randomUUID()
        val message = Component.text("same outgoing message")
        repeat(200) { registry.issue(playerId, message) }
        val executor = Executors.newFixedThreadPool(8)
        try {
            val attempts = (1..240).map {
                executor.submit<Boolean> { registry.consume(playerId, message) }
            }
            attempts.count { it.get(2, TimeUnit.SECONDS) } shouldBe 200
            registry.consume(playerId, message) shouldBe false
        } finally {
            executor.shutdownNow()
        }
    }
})
