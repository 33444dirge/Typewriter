package com.typewritermc.utils

import com.typewritermc.core.utils.point.Vector
import com.typewritermc.engine.paper.utils.BlockCollision
import com.typewritermc.engine.paper.utils.BlockSnapshot
import com.typewritermc.engine.paper.utils.BlockView
import com.typewritermc.engine.paper.utils.BukkitBlockGetter
import com.typewritermc.engine.paper.utils.BukkitBlockShape
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import org.bukkit.util.BoundingBox

class BlockSnapshotPhysicsSpec : FunSpec({
    test("collision shapes detach both their input and returned boxes") {
        val original = BoundingBox(0.0, 0.0, 0.0, 1.0, 0.5, 1.0)
        val shape = BukkitBlockShape(listOf(original))
        original.expand(20.0)
        shape.boundingBoxes().first().expand(30.0)
        shape.boundingBoxes().first().maxY shouldBe 0.5
        shape.boundingBoxes().first().minX shouldBe 0.0
    }

    test("snapshot rays respect negative block coordinates and solid obstacles") {
        val view = BlockView { x, y, z ->
            if (x == -2 && y == 64 && z == 0) {
                BlockSnapshot(x, y, z, Material.STONE, true, false, false, null,
                    listOf(BoundingBox(0.0, 0.0, 0.0, 1.0, 1.0, 1.0)))
            } else BlockSnapshot.empty(x, y, z)
        }
        val getter = BukkitBlockGetter(view)
        BlockCollision().isRayPathClear(Vector(-3.5, 64.5, 0.5), Vector(0.5, 64.5, 0.5), getter) shouldBe false
        BlockCollision().isRayPathClear(Vector(-3.5, 65.5, 0.5), Vector(0.5, 65.5, 0.5), getter) shouldBe true
        getter.blockAt(Vector(-1.2, 64.0, 0.0)).x shouldBe -2
    }
})
