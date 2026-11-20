package com.typewritermc.engine.paper.entry.entity

import com.typewritermc.core.utils.point.distanceSquared
import com.typewritermc.engine.paper.entry.entries.EntityProperty
import com.typewritermc.engine.paper.entry.entries.PropertyCollector
import com.typewritermc.engine.paper.entry.entries.PropertyCollectorSupplier
import com.typewritermc.engine.paper.entry.entries.PropertySupplier
import com.typewritermc.engine.paper.utils.toBukkitLocation
import com.typewritermc.engine.paper.utils.foliaScheduler
import com.typewritermc.engine.paper.utils.playStepSoundAt
import org.bukkit.Bukkit
import java.util.concurrent.atomic.AtomicBoolean
import org.bukkit.SoundCategory
import org.bukkit.entity.Player
import org.jetbrains.annotations.ApiStatus
import kotlin.reflect.KClass
import kotlin.reflect.full.companionObjectInstance

@ApiStatus.Internal
class DisplayEntity(
    private val player: Player,
    creator: EntityCreator,
    val activityManager: ActivityManager<*>,
    val collectors: List<PropertyCollector<*>>,
) {
    private val disposed = AtomicBoolean()
    private val lifecycleLock = Any()
    private var resourcesReleased = false
    private val pendingTick = AtomicBoolean()
    val entity = creator.create(player)

    var lastSoundLocation = activityManager.position
        private set

    val state: EntityState
        get() = entity.state

    val entityId: Int
        get() = entity.entityId

    init {
        entity.spawn(activityManager.position)
        try {
            applyProperties()
        } catch (throwable: Throwable) {
            // The entity is already spawned for the viewer but nothing will hold a reference to it,
            // leaving a copy behind that can never be despawned again.
            entity.dispose()
            throw throwable
        }
    }

    fun tick() {
        if (disposed.get()) return
        if (!Bukkit.isOwnedByCurrentRegion(player)) {
            if (!pendingTick.compareAndSet(false, true)) return
            foliaScheduler.atEntity(player, {
                pendingTick.set(false)
                tick()
            }, { pendingTick.set(false) }, 1L)
            return
        }
        synchronized(lifecycleLock) {
            if (disposed.get()) return
            applyProperties()
            entity.tick()

            if ((lastSoundLocation.distanceSquared(activityManager.position) ?: 0.0) > 1.7) {
                lastSoundLocation = activityManager.position
                val position = lastSoundLocation
                player.playStepSoundAt(position.toBukkitLocation()) {
                    !disposed.get() && lastSoundLocation == position
                }
            }
        }
    }

    private fun applyProperties() {
        val activeProperties = activityManager.activeProperties
        // Active properties should override the properties from the collectors
        val properties = collectors
            .filter { activeProperties.none { active -> it.type.isInstance(active) } }
            .mapNotNull { it.collect(player) }

        entity.consumeProperties(activeProperties + properties)
    }

    operator fun contains(entityId: Int): Boolean = entity.contains(entityId)

    fun <P : EntityProperty> property(type: KClass<P>) = entity.property(type)

    fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        if (!Bukkit.isOwnedByCurrentRegion(player)) {
            foliaScheduler.atEntity(player, { release(true) }, { release(false) }, 1L)
            return
        }
        release(true)
    }

    private fun release(onOwner: Boolean) = synchronized(lifecycleLock) {
        if (resourcesReleased) return@synchronized
        resourcesReleased = true
        try {
            if (onOwner) entity.dispose()
        } finally {
            entity.retireResources()
        }
    }
}

/**
 * Group suppliers to collectors based on what they supply.
 */
fun List<Pair<PropertySupplier<*>, Int>>.toCollectors(): List<PropertyCollector<EntityProperty>> {
    return groupBy { it.first.type() }
        .map { (type, suppliers) ->
            val typeSuppliers = suppliers.sortedByDescending { it.second }
                .map { it.first }

            val companion = type.companionObjectInstance
            if (companion !is PropertyCollectorSupplier<*>) {
                error("${type.simpleName} needs to have a companion object that implements PropertyCollectorSupplier")
            }

            @Suppress("UNCHECKED_CAST")
            val comp = companion as PropertyCollectorSupplier<in EntityProperty>
            comp.collector(typeSuppliers)
        }
}
