package com.typewritermc.engine.paper.utils

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.data.BlockData
import org.bukkit.util.BoundingBox

fun interface BlockView {
    fun getBlockAt(x: Int, y: Int, z: Int): BlockSnapshot
}

data class BlockCoordinate(val x: Int, val y: Int, val z: Int)

/** Detached geometry. Callers receive their own mutable Bukkit boxes. */
class CollisionSnapshot(boxes: Collection<BoundingBox>) {
    private val boxes = boxes.map { doubleArrayOf(it.minX, it.minY, it.minZ, it.maxX, it.maxY, it.maxZ) }
    val boundingBoxes: List<BoundingBox>
        get() = boxes.map { BoundingBox(it[0], it[1], it[2], it[3], it[4], it[5]) }
}

/** Contains no live Block, Chunk, Entity or World reference. */
class BlockSnapshot(
    val x: Int,
    val y: Int,
    val z: Int,
    val type: Material,
    val isSolid: Boolean,
    val isLiquid: Boolean,
    val isPassable: Boolean,
    data: BlockData?,
    boxes: Collection<BoundingBox>,
) {
    private val data = data?.clone()
    val blockData: BlockData? get() = data?.clone()
    val collisionShape = CollisionSnapshot(boxes)
    val location: Location get() = Location(null, x.toDouble(), y.toDouble(), z.toDouble())

    companion object {
        /** Must be called while the current thread owns the block. */
        fun capture(block: Block): BlockSnapshot = BlockSnapshot(
            block.x, block.y, block.z, block.type, block.isSolid, block.isLiquid, block.isPassable,
            block.blockData, block.collisionShape.boundingBoxes,
        )

        fun unknown(x: Int, y: Int, z: Int): BlockSnapshot = BlockSnapshot(
            x, y, z, Material.BARRIER, true, false, false, null,
            listOf(BoundingBox(0.0, 0.0, 0.0, 1.0, 1.0, 1.0)),
        )

        fun empty(x: Int, y: Int, z: Int): BlockSnapshot = BlockSnapshot(
            x, y, z, Material.VOID_AIR, false, false, true, null, emptyList(),
        )
    }
}

/** Legacy owner-only entry point; calculations crossing regions must supply a snapshot view. */
internal class OwnedBlockView(private val world: World) : BlockView {
    override fun getBlockAt(x: Int, y: Int, z: Int): BlockSnapshot {
        check(Bukkit.isOwnedByCurrentRegion(Location(world, x.toDouble(), y.toDouble(), z.toDouble()))) {
            "Block physics requires an owner-captured snapshot at $x, $y, $z"
        }
        return BlockSnapshot.capture(world.getBlockAt(x, y, z))
    }
}

/**
 * A bounded view for one search. Missing cells are conservatively blocked until [loadMissing]
 * captures them. A result must be discarded while [hasMissing] is true.
 */
class BlockSnapshots private constructor(
    private val world: World,
    val worldId: String,
    val minHeight: Int,
    val maxHeight: Int,
    private val maxBlocks: Int,
) : BlockView {
    private val blocks = HashMap<BlockCoordinate, BlockSnapshot>()
    private val missing = LinkedHashSet<BlockCoordinate>()
    val hasMissing: Boolean get() = missing.isNotEmpty()

    override fun getBlockAt(x: Int, y: Int, z: Int): BlockSnapshot {
        if (y !in minHeight until maxHeight) return BlockSnapshot.empty(x, y, z)
        val position = BlockCoordinate(x, y, z)
        return blocks[position] ?: run {
            val baseX = x shr 2 shl 2
            val baseY = y shr 2 shl 2
            val baseZ = z shr 2 shl 2
            for (bx in baseX until baseX + 4) for (by in baseY until baseY + 4) {
                if (by !in minHeight until maxHeight) continue
                for (bz in baseZ until baseZ + 4) {
                    val point = BlockCoordinate(bx, by, bz)
                    if (point !in blocks) missing.add(point)
                }
            }
            check(blocks.size + missing.size <= maxBlocks) { "Path block snapshot limit exceeded ($maxBlocks)" }
            BlockSnapshot.unknown(x, y, z)
        }
    }

    suspend fun loadMissing() {
        val requested = missing.toList()
        missing.clear()
        requested.groupBy { (it.x shr 4) to (it.z shr 4) }.forEach { (chunk, points) ->
            points.chunked(256).forEach { batch ->
                currentCoroutineContext().ensureActive()
                val target = Location(world, (chunk.first * 16).toDouble(), batch.first().y.toDouble(),
                    (chunk.second * 16).toDouble())
                val captured = withContext(target.regionDispatcher) {
                    world.getChunkAtAsync(chunk.first, chunk.second).await()
                    batch.associateWith { BlockSnapshot.capture(world.getBlockAt(it.x, it.y, it.z)) }
                }
                blocks.putAll(captured)
            }
        }
    }

    companion object {
        suspend fun create(origin: Location, maxBlocks: Int): BlockSnapshots {
            val target = origin.clone()
            val world = requireNotNull(target.world)
            val bounds = withContext(target.regionDispatcher) { Triple(world.uid.toString(), world.minHeight, world.maxHeight) }
            return BlockSnapshots(world, bounds.first, bounds.second, bounds.third, maxBlocks.coerceIn(64, 1_048_576))
        }
    }
}
