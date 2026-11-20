package com.typewritermc.basic.entries.cinematic

import com.typewritermc.core.utils.point.Position
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot

/** Immutable geometry; evaluating a shot never looks up a Bukkit world or loads a chunk. */
internal class StationaryCameraRoute(
    val start: Position,
    val end: Position,
    val target: Position,
    private val moveFrames: Int,
    val duration: Int,
    private val usePointRotation: Boolean = false,
) {
    init {
        require(start.world == end.world && start.world == target.world) { "Camera points must share a world" }
        require(listOf(start, end, target).all { it.x.isFinite() && it.y.isFinite() && it.z.isFinite() }) {
            "Camera points must be finite"
        }
        require(!usePointRotation || listOf(start, end).all {
            it.yaw.isFinite() && it.pitch.isFinite() && it.pitch in -90f..90f
        }) { "Camera rotations must be finite and pitch within -90..90" }
        require(moveFrames in 1..1200 && duration in moveFrames..1200) { "Camera duration must be 1..1200 frames" }
        require(listOf(start, end, target).all { kotlin.math.abs(it.x) <= 29_999_984 && kotlin.math.abs(it.z) <= 29_999_984 }) {
            "Camera points exceed the world boundary"
        }
    }

    fun position(frame: Int): Position {
        val t = (frame.toDouble() / moveFrames).coerceIn(0.0, 1.0)
        val smooth = t * t * (3 - 2 * t)
        val x = start.x + (end.x - start.x) * smooth
        val y = start.y + (end.y - start.y) * smooth
        val z = start.z + (end.z - start.z) * smooth
        if (usePointRotation) {
            val turn = ((end.yaw.toDouble() - start.yaw + 540.0) % 360.0 + 360.0) % 360.0 - 180.0
            return start.copy(
                x = x, y = y, z = z,
                yaw = (start.yaw + turn * smooth).toFloat(),
                pitch = (start.pitch + (end.pitch - start.pitch) * smooth).toFloat(),
            )
        }
        val dx = target.x - x
        val dy = target.y - y
        val dz = target.z - z
        return start.copy(
            x = x, y = y, z = z,
            yaw = Math.toDegrees(atan2(-dx, dz)).toFloat(),
            pitch = -Math.toDegrees(atan2(dy, hypot(dx, dz))).toFloat(),
        )
    }

    fun withinRange(origin: Position, maxDistance: Double): Boolean {
        if (!maxDistance.isFinite() || maxDistance !in 1.0..256.0 || origin.world != start.world) return false
        return listOf(start, end, target).all {
            val dx = it.x - origin.x
            val dy = it.y - origin.y
            val dz = it.z - origin.z
            dx * dx + dy * dy + dz * dz <= maxDistance * maxDistance
        }
    }

    /** Includes a one-chunk border around the entire route and the subject; bounded before allocation. */
    fun requiredChunks(): Set<Long> {
        val points = listOf(start, end, target)
        val minX = points.minOf { chunk(it.x) } - 1
        val maxX = points.maxOf { chunk(it.x) } + 1
        val minZ = points.minOf { chunk(it.z) } - 1
        val maxZ = points.maxOf { chunk(it.z) } + 1
        require((maxX.toLong() - minX + 1) * (maxZ.toLong() - minZ + 1) <= 1225) { "Camera area is too large" }
        return buildSet {
            for (x in minX..maxX) for (z in minZ..maxZ) add(chunkKey(x, z))
        }
    }

    companion object {
        private fun chunk(value: Double): Int = floor(value / 16.0).toInt()
        internal fun chunkKey(x: Int, z: Int): Long = (x.toLong() and 0xffffffffL) or (z.toLong() shl 32)
    }
}
