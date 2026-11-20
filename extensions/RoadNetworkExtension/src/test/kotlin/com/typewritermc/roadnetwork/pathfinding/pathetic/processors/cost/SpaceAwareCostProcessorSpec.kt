package com.typewritermc.roadnetwork.pathfinding.pathetic.processors.cost

import de.bsommerfeld.pathetic.api.pathing.processing.context.EvaluationContext
import de.bsommerfeld.pathetic.api.wrapper.PathPosition
import com.typewritermc.roadnetwork.pathfinding.pathetic.SnapshotEnvironmentContext
import com.typewritermc.engine.paper.utils.BlockView
import com.typewritermc.engine.paper.utils.BlockSnapshot
import com.typewritermc.engine.paper.utils.BlockCoordinate
import org.bukkit.util.BoundingBox
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Material

class SpaceAwareCostProcessorSpec : FunSpec() {
    private val walls = mutableSetOf<BlockCoordinate>()
    private val world = BlockView { x, y, z ->
        if (BlockCoordinate(x, y, z) in walls) {
            BlockSnapshot(x, y, z, Material.STONE, true, false, false, null,
                listOf(BoundingBox(0.0, 0.0, 0.0, 1.0, 1.0, 1.0)))
        } else BlockSnapshot.empty(x, y, z)
    }

    private val processor = SpaceAwareCostProcessor(entityHeight = 1.8)

    private fun contextAt(x: Int, y: Int, z: Int): EvaluationContext {
        val context = mockk<EvaluationContext>()
        every { context.currentPathPosition } returns PathPosition(x.toDouble(), y.toDouble(), z.toDouble())
        every { context.environmentContext } returns SnapshotEnvironmentContext(world)
        return context
    }

    private fun placeWall(x: Int, z: Int, baseY: Int = 64, height: Int = 3) {
        repeat(height) { offset ->
            walls.add(BlockCoordinate(x, baseY + offset, z))
        }
    }

    private fun costAt(x: Int, y: Int, z: Int): Double =
        processor.calculateCostContribution(contextAt(x, y, z)).value()

    init {
        beforeTest {
            walls.clear()
        }

        test("open space with no walls costs nothing") {
            costAt(0, 64, 0) shouldBe 0.0
        }

        test("a wall on only one side does not pull the path off its line") {
            for (z in -10..10) placeWall(2, z)

            costAt(0, 64, 0) shouldBe 0.0
            costAt(1, 64, 0) shouldBe 0.0
        }

        test("off-center positions in a corridor cost more than centered ones") {
            for (z in -10..10) {
                placeWall(-1, z)
                placeWall(7, z)
            }

            val centered = costAt(3, 64, 0)
            val offCenter = costAt(1, 64, 0)

            offCenter shouldBeGreaterThan centered
            offCenter shouldBeGreaterThan 0.0
        }

        test("tight corridors are not penalized so they stay navigable") {
            for (z in -10..10) {
                placeWall(-1, z)
                placeWall(1, z)
            }

            costAt(0, 64, 0) shouldBe 0.0
        }
    }
}
