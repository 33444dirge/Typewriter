package com.typewritermc.content

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.PacketEventsAPI
import com.github.retrooper.packetevents.manager.server.ServerVersion
import com.github.retrooper.packetevents.manager.server.ServerManager
import com.github.retrooper.packetevents.manager.protocol.ProtocolManager
import com.github.retrooper.packetevents.manager.player.PlayerManager
import com.github.retrooper.packetevents.injector.ChannelInjector
import com.github.retrooper.packetevents.protocol.item.ItemStack as PacketItem
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot
import com.typewritermc.core.interaction.context
import com.typewritermc.engine.paper.content.ContentContext
import com.typewritermc.engine.paper.content.ContentInteraction
import com.typewritermc.engine.paper.content.ContentMode
import com.typewritermc.engine.paper.content.components.IntractableItem
import com.typewritermc.engine.paper.extensions.packetevents.sendPacketTo
import com.typewritermc.engine.paper.utils.entityDispatcher
import com.typewritermc.engine.paper.content.inventoryPacketItem
import io.github.retrooper.packetevents.impl.netty.NettyManagerImpl
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.PlayerInventory
import java.time.Duration

private inline fun <reified T : Any> overlayMock(): T = try {
    mockk<T>()
} catch (error: Throwable) {
    throw AssertionError("Could not create inventory test double for ${T::class.qualifiedName}", error)
}

private class OverlayPacketApi : PacketEventsAPI<Any>() {
    private val owner = Any()
    private val netty = NettyManagerImpl()
    private val protocols = mockk<ProtocolManager>(relaxed = true)
    private val players = mockk<PlayerManager>(relaxed = true)
    private val injector = mockk<ChannelInjector>(relaxed = true)
    override fun getPlugin() = owner
    override fun getServerManager() = ServerManager { ServerVersion.V_26_2 }
    override fun getProtocolManager() = protocols
    override fun getPlayerManager() = players
    override fun getNettyManager() = netty
    override fun getInjector() = injector
    override fun isLoaded() = true
    override fun init() {}
    override fun isInitialized() = true
    override fun isTerminated() = false
}

class ContentInventoryOverlaySpec : FunSpec({
    val player = mockk<Player>()
    val inventory = mockk<PlayerInventory>()
    val mode = mockk<ContentMode>()
    val tool = overlayMock<ItemStack>()
    val original = overlayMock<ItemStack>()
    lateinit var packetTool: PacketItem
    lateinit var packetOriginal: PacketItem
    val previousApi = PacketEvents.getAPI()

    beforeEach {
        clearMocks(player, inventory, mode, tool, original)
        PacketEvents.setAPI(OverlayPacketApi())
        mockkStatic("com.typewritermc.engine.paper.content.ContentInventoryPacketsKt")
        mockkStatic("com.typewritermc.engine.paper.utils.PaperCoroutineKt")
        mockkStatic("com.typewritermc.engine.paper.extensions.packetevents.PlayerPacketsKt")
        packetTool = PacketItem.builder().type(ItemTypes.STONE).amount(1).build()
        packetOriginal = PacketItem.builder().type(ItemTypes.DIAMOND).amount(2).build()
        every { player.entityDispatcher } returns Dispatchers.Unconfined
        every { player.inventory } returns inventory
        every { inventory.getItem(0) } returns original
        every { tool.clone() } returns tool
        every { inventoryPacketItem(tool) } returns packetTool
        every { inventoryPacketItem(original) } returns packetOriginal
        every { any<WrapperPlayServerSetSlot>().sendPacketTo(player) } just Runs
        coEvery { mode.tick(any()) } just Runs
    }

    afterEach {
        unmockkStatic("com.typewritermc.engine.paper.content.ContentInventoryPacketsKt")
        unmockkStatic("com.typewritermc.engine.paper.utils.PaperCoroutineKt")
        unmockkStatic("com.typewritermc.engine.paper.extensions.packetevents.PlayerPacketsKt")
        PacketEvents.setAPI(previousApi)
    }

    test("showing editor tools leaves the server inventory untouched") {
        every { mode.items() } returns mapOf(0 to IntractableItem(tool) {})
        val interaction = ContentInteraction(ContentContext(), player, mode, context())
        interaction.tick(Duration.ofMillis(50))
        verify(exactly = 0) { inventory.setItem(any<Int>(), any()) }
        verify { match<WrapperPlayServerSetSlot> { it.slot == 0 && it.item == packetTool }.sendPacketTo(player) }
    }

    test("removing a tool sends the current real item without overwriting inventory changes") {
        every { mode.items() } returnsMany listOf(mapOf(0 to IntractableItem(tool) {}), emptyMap())
        val interaction = ContentInteraction(ContentContext(), player, mode, context())
        interaction.tick(Duration.ofMillis(50))
        interaction.tick(Duration.ofMillis(50))
        verify(exactly = 0) { inventory.setItem(any<Int>(), any()) }
        verify { match<WrapperPlayServerSetSlot> { it.slot == 0 && it.item == packetOriginal }.sendPacketTo(player) }
    }
})
