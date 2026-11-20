package com.typewritermc.basic.entries.audience

import com.typewritermc.core.entries.Ref
import com.typewritermc.engine.paper.entry.entries.AudienceFilter
import com.typewritermc.engine.paper.entry.entries.AudienceFilterEntry
import com.typewritermc.engine.paper.entry.entries.TickableDisplay
import com.typewritermc.engine.paper.entry.entries.Var
import org.bukkit.entity.Player
import java.util.*

abstract class CappedAudienceFilter(
    ref: Ref<out AudienceFilterEntry>,
    val capacity: Var<Int>,
) : AudienceFilter(ref), TickableDisplay {
    @Volatile protected var candidates = listOf<UUID>()
    private val capacities = java.util.concurrent.ConcurrentHashMap<UUID, Int>()

    protected fun sampledCapacity(): Int =
        consideredPlayers.firstOrNull()?.let { capacities[it.uniqueId] } ?: 0

    protected open fun refreshPlayer(player: Player) {}

    override fun filter(player: Player): Boolean = candidates.contains(player.uniqueId)


    override fun onPlayerAdd(player: Player) {
        capacities[player.uniqueId] = capacity.get(player).coerceAtLeast(0)
        refreshPlayer(player)
        refreshCandidates()
        super.onPlayerAdd(player)
    }

    override fun tick() {
        forEachConsideredPlayer { player ->
            capacities[player.uniqueId] = capacity.get(player).coerceAtLeast(0)
            refreshPlayer(player)
        }
        val players = consideredPlayers
        val targetCapacity = sampledCapacity()
        if (targetCapacity < candidates.size) {
            refreshCandidates()
        }
        // If there are more people, but we haven't considered them yet, we should update the candidates
        if (targetCapacity > candidates.size && players.size > candidates.size) {
            refreshCandidates()
        }
    }

    override fun onPlayerRemove(player: Player) {
        super.onPlayerRemove(player)
        capacities.remove(player.uniqueId)
        refreshCandidates()
    }

    protected fun refreshCandidates() {
        updateCandidates()
        forEachConsideredPlayer { it.refresh() }
    }

    protected abstract fun updateCandidates()
}
