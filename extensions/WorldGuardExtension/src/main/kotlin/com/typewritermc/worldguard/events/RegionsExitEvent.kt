package com.typewritermc.worldguard.events

import com.sk89q.worldguard.protection.regions.ProtectedRegion
import org.bukkit.entity.Player
import org.bukkit.event.HandlerList
import org.bukkit.event.player.PlayerEvent

class RegionsExitEvent(player: Player, regions: Set<ProtectedRegion>) : PlayerEvent(player) {
    val regions: Set<ProtectedRegion> = java.util.Collections.unmodifiableSet(regions.toSet())
    private val regionNames = regions.mapTo(hashSetOf()) { it.id }
    operator fun contains(regionName: String) = regionName in regionNames

    override fun getHandlers(): HandlerList = HANDLER_LIST

    companion object {
        @JvmStatic
        val HANDLER_LIST = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = HANDLER_LIST
    }
}
