package com.typewritermc.roadnetwork.pathfinding.pathetic.processors

import de.bsommerfeld.pathetic.api.pathing.processing.context.EvaluationContext
import de.bsommerfeld.pathetic.api.pathing.processing.context.SearchContext
import de.bsommerfeld.pathetic.api.wrapper.PathPosition
import com.typewritermc.roadnetwork.pathfinding.pathetic.SnapshotEnvironmentContext
import com.typewritermc.engine.paper.utils.BlockView
import com.typewritermc.engine.paper.utils.BlockSnapshot
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Material
import org.bukkit.util.BoundingBox
import com.typewritermc.roadnetwork.pathfinding.pathetic.properties.PathNodeProperty
import it.unimi.dsi.fastutil.longs.Long2ObjectMap
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet
import it.unimi.dsi.fastutil.objects.ObjectSet
import java.util.UUID

class ReachabilityProcessorSpec : FunSpec({
    val fullBlock = BoundingBox(0.0, 0.0, 0.0, 1.0, 1.0, 1.0)

    fun worldWithStone(stone: Set<Triple<Int, Int, Int>>): BlockView = BlockView { x, y, z ->
        val isStone = Triple(x, y, z) in stone
        BlockSnapshot(x, y, z, if (isStone) Material.STONE else Material.AIR,
            isStone, false, !isStone, null, if (isStone) listOf(fullBlock) else emptyList())
    }

    fun floorAndBlocks(vararg blocks: Triple<Int, Int, Int>): Set<Triple<Int, Int, Int>> {
        val floor = (-3..3).flatMap { x -> (-3..3).map { z -> Triple(x, 63, z) } }
        return (floor + blocks).toSet()
    }

    fun isReachable(world: BlockView, from: PathPosition, to: PathPosition): Boolean {
        val sharedData = mutableMapOf<String, Any>()
        val searchContext = mockk<SearchContext> { every { this@mockk.sharedData } returns sharedData }
        val nodeTypeProcessor = NodeTypeProcessor()
        val obstructionProcessor = ObstructionProcessor()
        val reachabilityProcessor = ReachabilityProcessor()
        nodeTypeProcessor.initializeSearch(searchContext)
        obstructionProcessor.initializeSearch(searchContext)

        fun contextFor(position: PathPosition, previous: PathPosition?) = mockk<EvaluationContext> {
            every { currentPathPosition } returns position
            every { previousPathPosition } returns previous
            every { environmentContext } returns SnapshotEnvironmentContext(
                world, UUID.randomUUID().toString(),
            )
            every { this@mockk.sharedData } returns sharedData
        }

        listOf(contextFor(from, null), contextFor(to, from)).forEach { context ->
            val position = context.currentPathPosition
            val packed = (((position.flooredX + 33554432L) and 0x3FFFFFF) shl 38) or
                (((position.flooredZ + 33554432L) and 0x3FFFFFF) shl 12) or
                ((position.flooredY + 2048L) and 0xFFF)
            @Suppress("UNCHECKED_CAST")
            val cache = sharedData[NodeTypeProcessor::class.java.packageName + ".propertyCache"] as
                Long2ObjectMap<ObjectSet<PathNodeProperty>>
            cache[packed] = ObjectOpenHashSet<PathNodeProperty>().apply {
                add(PathNodeProperty.WALKABLE_SURFACE)
                if (world.getBlockAt(position.flooredX, position.flooredY, position.flooredZ).isSolid) {
                    add(PathNodeProperty.SOLID)
                }
            }
            obstructionProcessor.isValid(context)
        }
        return reachabilityProcessor.isValid(contextFor(to, from))
    }

    test("a straight jump onto a block is reachable") {
        val world = worldWithStone(floorAndBlocks(Triple(1, 64, 0)))

        isReachable(world, PathPosition(0.0, 64.0, 0.0), PathPosition(1.0, 65.0, 0.0)).shouldBeTrue()
    }

    test("a diagonal jump over a block corner is reachable") {
        val world = worldWithStone(floorAndBlocks(Triple(1, 64, 1), Triple(1, 64, 0)))

        isReachable(world, PathPosition(0.0, 64.0, 0.0), PathPosition(1.0, 65.0, 1.0)).shouldBeTrue()
    }

    test("a diagonal jump past the corner of a wall is not reachable") {
        val world = worldWithStone(floorAndBlocks(Triple(1, 64, 1), Triple(1, 64, 0), Triple(1, 65, 0)))

        isReachable(world, PathPosition(0.0, 64.0, 0.0), PathPosition(1.0, 65.0, 1.0)).shouldBeFalse()
    }
})
