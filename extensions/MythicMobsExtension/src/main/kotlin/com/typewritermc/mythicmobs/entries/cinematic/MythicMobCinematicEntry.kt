package com.typewritermc.mythicmobs.entries.cinematic

import com.typewritermc.core.books.pages.Colors
import com.typewritermc.core.extension.annotations.Entry
import com.typewritermc.core.extension.annotations.Placeholder
import com.typewritermc.core.extension.annotations.Segments
import com.typewritermc.core.extension.annotations.WithRotation
import com.typewritermc.core.utils.point.Position
import com.typewritermc.engine.paper.entry.Criteria
import com.typewritermc.engine.paper.entry.entries.*
import com.typewritermc.engine.paper.entry.temporal.SimpleCinematicAction
import com.typewritermc.engine.paper.extensions.placeholderapi.parsePlaceholders
import com.typewritermc.engine.paper.plugin
import com.typewritermc.engine.paper.utils.entityDispatcher
import com.typewritermc.engine.paper.utils.foliaScheduler
import com.typewritermc.engine.paper.utils.regionDispatcher
import com.typewritermc.engine.paper.utils.toBukkitLocation
import io.lumine.mythic.api.mobs.entities.SpawnReason
import io.lumine.mythic.bukkit.BukkitAdapter
import io.lumine.mythic.bukkit.MythicBukkit
import io.lumine.mythic.core.mobs.ActiveMob
import com.typewritermc.mythicmobs.removeOnOwner
import kotlinx.coroutines.withContext
import org.bukkit.entity.Player
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@Entry("mythicmob_cinematic", "Spawn a MythicMob during a cinematic", Colors.PURPLE, "fa6-solid:dragon")
/**
 * The `Spawn MythicMob Cinematic` cinematic entry spawns a MythicMob during a cinematic.
 *
 * ## How could this be used?
 *
 * This can be used to animate a MythicMob spawning during a cinematic.
 */
class MythicMobCinematicEntry(
    override val id: String = "",
    override val name: String = "",
    override val criteria: List<Criteria> = emptyList(),
    @Segments(Colors.PURPLE, "fa6-solid:dragon")
    val segments: List<MythicMobSegment> = emptyList(),
) : CinematicEntry {
    override fun create(player: Player): CinematicAction {
        return MobCinematicAction(player, this)
    }
}

data class MythicMobSegment(
    override val startFrame: Int = 0,
    override val endFrame: Int = 0,
    @Placeholder
    val mobName: Var<String> = ConstVar(""),
    @WithRotation
    val location: Var<Position> = ConstVar(Position.ORIGIN),
) : Segment

class MobCinematicAction(
    private val player: Player,
    entry: MythicMobCinematicEntry,
) : SimpleCinematicAction<MythicMobSegment>() {
    override val segments: List<MythicMobSegment> = entry.segments

    private var currentSpawn: Spawn? = null

    private class Spawn {
        val active = AtomicBoolean(true)
        val mob = AtomicReference<ActiveMob?>()

        fun close() {
            active.set(false)
            mob.getAndSet(null)?.removeOnOwner(despawn = true)
        }
    }

    override suspend fun startSegment(segment: MythicMobSegment) {
        super.startSegment(segment)

        val spawn = Spawn()
        currentSpawn = spawn
        try {
            val (type, target) = withContext(player.entityDispatcher) {
                val type = MythicBukkit.inst().mobManager
                    .getMythicMob(segment.mobName.get(player).parsePlaceholders(player)).orElse(null)
                type to segment.location.get(player).toBukkitLocation()
            }
            if (type == null) return
            withContext(target.regionDispatcher) {
                if (!spawn.active.get()) return@withContext
                val mob = type.spawn(BukkitAdapter.adapt(target), 1.0, SpawnReason.OTHER) {
                    it.isVisibleByDefault = false
                } ?: return@withContext
                spawn.mob.set(mob)
                if (!spawn.active.get()) {
                    spawn.close()
                    return@withContext
                }
                val entity = mob.cachedEntity?.bukkitEntity ?: return@withContext
                foliaScheduler.atEntity(player, {
                    if (spawn.active.get() && spawn.mob.get() === mob) player.showEntity(plugin, entity)
                }, null, 1L)
            }
        } catch (error: Throwable) {
            spawn.close()
            throw error
        }
    }

    override suspend fun stopSegment(segment: MythicMobSegment) {
        super.stopSegment(segment)

        currentSpawn?.close()
        currentSpawn = null
    }
}
