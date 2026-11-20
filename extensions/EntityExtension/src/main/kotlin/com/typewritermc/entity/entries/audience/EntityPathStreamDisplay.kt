package com.typewritermc.entity.entries.audience

import com.typewritermc.core.utils.point.Position
import com.typewritermc.engine.paper.entry.entity.*
import com.typewritermc.engine.paper.entry.entries.EntityDefinitionEntry
import com.typewritermc.engine.paper.entry.entries.EntityProperty
import com.typewritermc.engine.paper.entry.entries.PropertyCollector
import com.typewritermc.roadnetwork.entries.PathStreamDisplay
import org.bukkit.entity.Player

class EntityPathStreamDisplay(
    val player: Player,
    val definition: EntityDefinitionEntry,
) : PathStreamDisplay {
    private val lifecycleLock = Any()
    private var disposed = false
    private var entity: FakeEntity? = null
    private var collectors: List<PropertyCollector<EntityProperty>> = emptyList()
    private var lastPosition: PositionProperty = PositionProperty.Companion.ORIGIN

    override fun display(position: Position) = synchronized(lifecycleLock) {
        if (disposed) return@synchronized
        lastPosition = position.toProperty()
        if (entity == null) {
            val prioritizedPropertySuppliers = definition.data.withPriority() +
                    (FakeProvider(PositionProperty::class) { lastPosition } to Int.MAX_VALUE)

            collectors = prioritizedPropertySuppliers.toCollectors()
            entity = definition.create(player)
            entity?.spawn(lastPosition)
        }

        val collectedProperties = collectors.mapNotNull { it.collect(player) }
        entity?.consumeProperties(collectedProperties)
        entity?.tick()
        Unit
    }

    override fun dispose() = close(true)

    override fun retireResources() = close(false)

    private fun close(onOwner: Boolean) = synchronized(lifecycleLock) {
        if (disposed) return@synchronized
        disposed = true
        val previous = entity
        entity = null
        collectors = emptyList()
        try {
            if (onOwner) previous?.dispose()
        } finally {
            previous?.retireResources()
        }
        Unit
    }
}
