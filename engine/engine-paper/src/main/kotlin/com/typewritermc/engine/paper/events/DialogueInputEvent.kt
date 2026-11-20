package com.typewritermc.engine.paper.events

import com.typewritermc.engine.paper.utils.foliaScheduler
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.HandlerList
import org.bukkit.event.player.PlayerEvent

/** Observes accepted dialogue input; listeners cannot confirm choices or issue rewards. */
class DialogueInputEvent(player: Player, val entryId: String, val action: Action) :
    PlayerEvent(player, !Bukkit.isOwnedByCurrentRegion(player)) {
    enum class Action { OPEN, SELECT, CONFIRM, EXIT }

    override fun getHandlers(): HandlerList = HANDLER_LIST

    companion object {
        @JvmStatic
        val HANDLER_LIST = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = HANDLER_LIST
    }
}

fun Player.signalDialogueInput(entryId: String, action: DialogueInputEvent.Action) {
    if (DialogueInputEvent.getHandlerList().registeredListeners.isEmpty() || !foliaScheduler.owns(this)) return
    DialogueInputEvent(this, entryId, action).callEvent()
}
