package com.typewritermc.worldguard

import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldguard.WorldGuard
import com.sk89q.worldguard.protection.regions.ProtectedRegion
import com.typewritermc.core.extension.Initializable
import com.typewritermc.core.extension.annotations.Singleton
import com.typewritermc.engine.paper.logger
import com.typewritermc.engine.paper.plugin
import com.typewritermc.engine.paper.utils.entityDispatcher
import com.typewritermc.engine.paper.utils.foliaScheduler
import com.typewritermc.engine.paper.utils.server
import com.typewritermc.worldguard.events.RegionsEnterEvent
import com.typewritermc.worldguard.events.RegionsExitEvent
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import kotlinx.coroutines.withContext
import lirand.api.extensions.events.unregister
import lirand.api.extensions.server.registerEvents
import org.bukkit.entity.Player
import org.bukkit.Location
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerMoveEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level

@Singleton
class WorldGuardInitializer : Initializable, Listener {
    private class Watch {
        val membership = RegionMembership()
        @Volatile var task: ScheduledTask? = null
        var reportedError = false
    }

    private val active = AtomicBoolean()
    private val watchers = ConcurrentHashMap<UUID, Watch>()

    override suspend fun initialize() {
        if (!active.compareAndSet(false, true)) return
        plugin.registerEvents(this)
        server.onlinePlayers.toList().forEach { player ->
            withContext(player.entityDispatcher) { start(player) }
        }
    }

    private fun start(player: Player) {
        if (!active.get()) return
        val id = player.uniqueId
        val watch = Watch()
        if (watchers.putIfAbsent(id, watch) != null) return
        sample(player, watch)
        watch.task = foliaScheduler.repeatEntity(player, { task ->
            if (!active.get() || watchers[id] !== watch) task.cancel()
            else sample(player, watch)
        }, { watchers.remove(id, watch) }, 1L, 1L)
        if (!active.get() || watchers[id] !== watch) watch.task?.cancel()
    }

    private fun sample(player: Player, watch: Watch, location: Location = player.location) {
        if (!active.get() || watchers[player.uniqueId] !== watch) return
        try {
            val regions = WorldGuard.getInstance().platform.regionContainer.createQuery()
                .getApplicableRegions(BukkitAdapter.adapt(location)).regions
            val changes = watch.membership.update(location.world.uid, regions)
            watch.reportedError = false
            if (!active.get() || watchers[player.uniqueId] !== watch) return
            if (changes.exited.isNotEmpty()) RegionsExitEvent(player, changes.exited).callEvent()
            if (!active.get() || watchers[player.uniqueId] !== watch) return
            if (changes.entered.isNotEmpty()) RegionsEnterEvent(player, changes.entered).callEvent()
        } catch (error: Exception) {
            if (!watch.reportedError) {
                watch.reportedError = true
                logger.log(Level.WARNING, "Failed to sample Typewriter WorldGuard regions for ${player.uniqueId}", error)
            }
        }
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) = start(event.player)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        val watch = watchers[event.player.uniqueId] ?: return
        sample(event.player, watch, event.to)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        watchers.remove(event.player.uniqueId)?.task?.cancel()
    }

    override suspend fun shutdown() {
        active.set(false)
        unregister()
        val previous = watchers.values.toList()
        watchers.clear()
        previous.forEach { watch ->
            try {
                watch.task?.cancel()
            } catch (error: Exception) {
                logger.log(Level.WARNING, "Failed to cancel Typewriter WorldGuard region sampling", error)
            }
        }
    }
}

internal data class RegionChanges(val entered: Set<ProtectedRegion>, val exited: Set<ProtectedRegion>)

/** Compares owner samples without retaining WorldGuard's mutable result collection. */
internal class RegionMembership {
    private var worldId: UUID? = null
    private var previous = emptyMap<String, ProtectedRegion>()

    fun update(world: UUID, regions: Set<ProtectedRegion>): RegionChanges {
        val next = regions.associateBy { it.id }
        val changes = when (worldId) {
            null -> RegionChanges(emptySet(), emptySet())
            world -> RegionChanges(
                next.filterKeys { it !in previous }.values.toSet(),
                previous.filterKeys { it !in next }.values.toSet(),
            )
            else -> RegionChanges(next.values.toSet(), previous.values.toSet())
        }
        worldId = world
        previous = next
        return changes
    }
}
