package com.typewritermc.entity

import com.github.retrooper.packetevents.protocol.player.User
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChatMessage
import com.typewritermc.engine.paper.interaction.ChatHistory
import com.typewritermc.engine.paper.interaction.Message
import com.typewritermc.engine.paper.interaction.ResendTokenRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.netty.channel.embedded.EmbeddedChannel
import net.kyori.adventure.text.Component
import java.util.UUID

class ChatHistoryBoundsSpec : FunSpec({
    test("signed overflow preserves packet order and each original can only be sent once") {
        val history = ChatHistory(3, 1000)
        val registry = ResendTokenRegistry()
        val channel = EmbeddedChannel()
        val sent = mutableListOf<WrapperPlayServerChatMessage>()
        val user = mockk<User>()
        every { user.uuid } returns UUID.randomUUID()
        every { user.channel } returns channel
        every { user.sendPacket(any<WrapperPlayServerChatMessage>()) } answers { sent += firstArg<WrapperPlayServerChatMessage>() }
        val messages = List(10) { Message.PlayerMessage(Component.text("$it"), mockk(), 10) }
        try {
            history.startBlocking()
            val expected = messages.take(7).map { it.packet }
            messages.forEach(history::addMessage)
            history.drainPackets(user, registry)
            sent shouldBe expected
            history.hasMessage(Component.text("0")) shouldBe false
            history.hasMessage(Component.text("9")) shouldBe true
            messages.take(7).forEach { history.sendSignedMessage(it, user, registry) shouldBe false }
            history.drainPackets(user, registry)
            sent shouldBe expected
        } finally {
            history.close()
            channel.finishAndReleaseAll()
        }
    }

    test("the byte bound trims large components and a closed history accepts no late writes") {
        val history = ChatHistory(10, 100)
        history.addMessage(Message.TextMessage(Component.text("older"), 60))
        history.addMessage(Message.TextMessage(Component.text("newer"), 60))
        history.hasMessage(Component.text("older")) shouldBe false
        history.hasMessage(Component.text("newer")) shouldBe true
        history.close()
        history.addMessage(Message.TextMessage(Component.text("late"), 1))
        history.hasMessage(Component.text("late")) shouldBe false
        history.hasMessage(Component.text("newer")) shouldBe false
    }
    test("a blocked signature backlog is bounded and disconnects once without replaying out of order") {
        var overloads = 0
        val history = ChatHistory(1, 10000, 2, 10000) { overloads++ }
        val registry = ResendTokenRegistry()
        val channel = EmbeddedChannel()
        val user = mockk<User>()
        every { user.channel } returns channel
        history.startBlocking()
        try {
            repeat(4) { history.addMessage(Message.PlayerMessage(Component.text("$it"), mockk(), 10)) }
            history.drainPackets(user, registry)
            channel.isActive shouldBe false
            overloads shouldBe 1
            history.drainPackets(user, registry)
            overloads shouldBe 1
            repeat(4) { history.hasMessage(Component.text("$it")) shouldBe false }
            history.addMessage(Message.TextMessage(Component.text("late"), 1))
            history.hasMessage(Component.text("late")) shouldBe false
        } finally {
            history.close()
            channel.finishAndReleaseAll()
        }
    }

    test("the pending byte budget trips independently of the packet count") {
        val history = ChatHistory(1, 10000, 100, 300)
        val channel = EmbeddedChannel()
        val user = mockk<User>()
        every { user.channel } returns channel
        history.startBlocking()
        try {
            repeat(3) { history.addMessage(Message.PlayerMessage(Component.text("$it"), mockk(), 1)) }
            history.drainPackets(user, ResendTokenRegistry())
            channel.isActive shouldBe false
        } finally {
            history.close()
            channel.finishAndReleaseAll()
        }
    }
})
