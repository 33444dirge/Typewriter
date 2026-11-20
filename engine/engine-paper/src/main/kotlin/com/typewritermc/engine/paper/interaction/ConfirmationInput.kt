package com.typewritermc.engine.paper.interaction

import com.destroystokyo.paper.event.player.PlayerJumpEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketType.Play
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerInput
import com.typewritermc.engine.paper.plugin
import com.typewritermc.engine.paper.utils.server
import lirand.api.extensions.events.unregister
import org.bukkit.NamespacedKey
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeModifier
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.event.player.PlayerToggleSneakEvent
import org.bukkit.inventory.EquipmentSlot
import com.typewritermc.engine.paper.utils.foliaScheduler
import java.util.concurrent.atomic.AtomicBoolean
import java.util.UUID

/**
 * How key presses are picked up from a player.
 *
 * Taking over a player's input takes most of their keys with it, so an implementation lists the keys
 * it can still pick up in [available].
 */
interface ConfirmationInput {
    /** The keys this can pick up, most preferred first. Never empty. */
    val available: List<ConfirmationKey>

    /**
     * Calls [onPress] for every press of [key], until the returned registration is disposed.
     *
     * [onPress] runs on the thread the press arrives on, which is the netty thread for an input read
     * off packets. Switch to the server thread before touching anything that needs it.
     */
    fun listen(key: ConfirmationKey, onPress: () -> Unit): ConfirmationRegistration
}

/** Has to be disposed once whatever it was created for is over. */
fun interface ConfirmationRegistration {
    fun dispose()
}

/** Picks up every key, off the events a player who still controls themselves sends. */
class BukkitConfirmationInput(private val player: Player) : ConfirmationInput {
    override val available: List<ConfirmationKey> = ConfirmationKey.entries

    override fun listen(key: ConfirmationKey, onPress: () -> Unit): ConfirmationRegistration {
        val handler = when (key) {
            ConfirmationKey.JUMP -> JumpHandler(player, onPress)
            ConfirmationKey.SNEAK -> SneakHandler(player, onPress)
            ConfirmationKey.SWAP_HANDS -> SwapHandsHandler(player, onPress)
            ConfirmationKey.LEFT_CLICK -> ClickHandler(player, onPress, Action.LEFT_CLICK_AIR, Action.LEFT_CLICK_BLOCK)
            ConfirmationKey.RIGHT_CLICK -> ClickHandler(player, onPress, Action.RIGHT_CLICK_AIR, Action.RIGHT_CLICK_BLOCK)
        }
        handler.initialize()
        return ConfirmationRegistration { handler.dispose() }
    }
}

/**
 * Picks up a jump or a sneak off the player input packet.
 *
 * A client that no longer controls its own body keeps sending this packet, but stops sending the
 * click and swap hand packets, so jump and sneak are the only keys left of the five.
 */
class PlayerInputConfirmationInput(private val player: Player) : ConfirmationInput {
    override val available: List<ConfirmationKey> = listOf(ConfirmationKey.JUMP, ConfirmationKey.SNEAK)

    override fun listen(key: ConfirmationKey, onPress: () -> Unit): ConfirmationRegistration {
        require(key in available) { "The player input packet does not carry $key" }
        val wasPressed = AtomicBoolean()
        val active = AtomicBoolean(true)
        val bundle = player.interceptPackets {
            Play.Client.PLAYER_INPUT { event ->
                val packet = WrapperPlayClientPlayerInput(event)
                val pressed = if (key == ConfirmationKey.JUMP) packet.isJump else packet.isShift
                // The packet carries every input at once, so it is resent while the key is held down.
                if (pressed && !wasPressed.getAndSet(pressed)) {
                    foliaScheduler.atEntity(player, { if (active.get()) onPress() }, null, 1L)
                } else if (!pressed) {
                    wasPressed.set(false)
                }
            }
        }
        return ConfirmationRegistration {
            active.set(false)
            bundle.cancel()
        }
    }
}

private sealed interface KeyHandler : Listener {
    val player: Player
    val onPress: () -> Unit

    fun initialize() {
        server.pluginManager.registerEvents(this, plugin)
    }

    fun dispose() {
        unregister()
    }
}

private class SwapHandsHandler(override val player: Player, override val onPress: () -> Unit) : KeyHandler {
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onSwapHands(event: PlayerSwapHandItemsEvent) {
        if (event.player.uniqueId != player.uniqueId) return
        event.isCancelled = true
        onPress()
    }
}

private class JumpHandler(override val player: Player, override val onPress: () -> Unit) : KeyHandler {
    private val key = NamespacedKey(plugin, "jump_confirmation/${UUID.randomUUID()}")

    override fun initialize() {
        super.initialize()
        player.getAttribute(Attribute.JUMP_STRENGTH)?.let { attribute ->
            attribute.removeModifier(NamespacedKey(plugin, "jump_confirmation"))
            attribute.addTransientModifier(AttributeModifier(key, -0.999, AttributeModifier.Operation.MULTIPLY_SCALAR_1))
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onJump(event: PlayerJumpEvent) {
        if (event.player.uniqueId != player.uniqueId) return
        onPress()
    }

    override fun dispose() {
        super.dispose()
        val remove = Runnable { player.getAttribute(Attribute.JUMP_STRENGTH)?.removeModifier(key) }
        if (foliaScheduler.owns(player)) remove.run()
        else foliaScheduler.atEntity(player, remove, null, 1L)
    }
}

private class SneakHandler(override val player: Player, override val onPress: () -> Unit) : KeyHandler {
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onSneak(event: PlayerToggleSneakEvent) {
        if (event.player.uniqueId != player.uniqueId) return
        if (!event.isSneaking) return
        event.isCancelled = true
        onPress()
    }
}

private class ClickHandler(
    override val player: Player,
    override val onPress: () -> Unit,
    private vararg val actions: Action,
) : KeyHandler {
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.player.uniqueId != player.uniqueId) return
        if (event.hand != EquipmentSlot.HAND) return
        if (event.action !in actions) return
        event.isCancelled = true
        onPress()
    }
}
