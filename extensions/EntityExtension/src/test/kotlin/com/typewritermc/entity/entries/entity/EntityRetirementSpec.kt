package com.typewritermc.entity.entries.entity

import com.typewritermc.core.utils.point.Position
import com.typewritermc.engine.paper.entry.entity.FakeEntity
import com.typewritermc.engine.paper.entry.entries.EntityDefinitionEntry
import com.typewritermc.engine.paper.entry.entries.EntityProperty
import com.typewritermc.entity.entries.audience.EntityPathStreamDisplay
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.*
import me.tofaa.entitylib.container.EntityContainer
import me.tofaa.entitylib.wrapper.WrapperEntity
import org.bukkit.entity.Player
import java.util.UUID

class EntityRetirementSpec : FunSpec({
    test("retirement detaches the library registry and viewers without despawning or sending packets") {
        val container = EntityContainer.basic()
        val entity = mockk<WrapperEntity>(relaxed = true)
        val viewer = UUID.randomUUID()
        val viewers = mutableSetOf(viewer)
        every { entity.entityId } returns 42
        every { entity.uuid } returns UUID.randomUUID()
        every { entity.parentContainer } returns container
        every { entity.viewers } returns viewers
        every { entity.removeViewerSilently(any<UUID>()) } answers { viewers.remove(firstArg()); Unit }
        container.addEntity(entity)

        entity.releaseWithoutViewer()

        container.containsEntity(42) shouldBe false
        viewers.isEmpty() shouldBe true
        verify(exactly = 0) { entity.despawn() }
        verify(exactly = 0) { entity.remove() }
        verify(exactly = 0) { entity.removeViewer(any<UUID>()) }
    }

    test("retiring an unused wrapper does not allocate a new packet entity") {
        val entity = object : WrapperFakeEntity(mockk(), mockk()) {
            override fun applyProperty(property: EntityProperty) {}
        }
        entity.retireResources()
        entity.retireResources()
    }

    test("retiring a path display releases its entity once and prevents later display resurrection") {
        val player = mockk<Player>()
        val definition = mockk<EntityDefinitionEntry>()
        val entity = mockk<FakeEntity>(relaxed = true)
        every { definition.data } returns emptyList()
        every { definition.create(player) } returns entity
        val display = EntityPathStreamDisplay(player, definition)
        display.display(Position.ORIGIN)

        display.retireResources()
        display.dispose()
        display.display(Position.ORIGIN)

        verify(exactly = 1) { definition.create(player) }
        verify(exactly = 1) { entity.retireResources() }
        verify(exactly = 0) { entity.dispose() }
        verify(exactly = 1) { entity.tick() }
    }
})
