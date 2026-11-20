package com.typewritermc.engine.paper.events

import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.Cancellable
import org.bukkit.event.HandlerList
import org.bukkit.event.player.PlayerEvent

/**
 * An optional presentation request. Cancel only after displaying this frame.
 * Story state, confirmation input and rewards remain owned by Typewriter.
 * Handlers must render synchronously in the player's owning context or leave this request untouched.
 */
class DialogueDisplayEvent(
    player: Player,
    val entryId: String,
    val speaker: Component,
    val message: Component,
    choices: List<Component>,
    val selectedIndex: Int,
    val totalChoices: Int,
    val choiceOffset: Int,
    val confirmationKey: Component,
    val animationComplete: Boolean,
    val finalDialogue: Boolean,
) : PlayerEvent(player, !Bukkit.isOwnedByCurrentRegion(player)), Cancellable {
    val choices: List<Component> = java.util.List.copyOf(choices)
    /** Transported by Typewriter so its action-bar guard accepts the external presentation. */
    var actionBarMessage: Component? = null
    private var displayed = false

    override fun isCancelled(): Boolean = displayed
    override fun setCancelled(cancel: Boolean) { displayed = cancel }
    override fun getHandlers(): HandlerList = HANDLER_LIST

    companion object {
        @JvmStatic
        val HANDLER_LIST = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = HANDLER_LIST
    }
}

/** Releases a frame only when its entry ID still owns that player's overlay. */
class DialogueDisplayCloseEvent(player: Player, val entryId: String) :
    PlayerEvent(player, !Bukkit.isOwnedByCurrentRegion(player)) {
    var actionBarMessage: Component? = null
    override fun getHandlers(): HandlerList = HANDLER_LIST

    companion object {
        @JvmStatic
        val HANDLER_LIST = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = HANDLER_LIST
    }
}
