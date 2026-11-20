package com.typewritermc.interaction

import com.github.retrooper.packetevents.event.PacketReceiveEvent
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.protocol.ConnectionState
import com.typewritermc.engine.paper.interaction.PacketInterceptor
import io.kotest.core.spec.style.FunSpec
import io.mockk.every
import io.mockk.mockk
import org.bukkit.entity.Player

class PacketInterceptorSpec : FunSpec({
    test("a play disconnect packet may have no Bukkit player") {
        val event = mockk<PacketSendEvent>()
        every { event.connectionState } returns ConnectionState.PLAY
        every<Any?> { event.getPlayer<Any>() } returns null

        PacketInterceptor().onPacketSend(event)
    }

    test("an inbound play packet may outlive its Bukkit player") {
        val event = mockk<PacketReceiveEvent>()
        every { event.connectionState } returns ConnectionState.PLAY
        every<Any?> { event.getPlayer<Any>() } returns null

        PacketInterceptor().onPacketReceive(event)
    }
})
