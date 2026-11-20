package com.typewritermc.basic.entries.cinematic

import com.typewritermc.core.utils.point.Position
import com.typewritermc.core.utils.point.World
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.math.cos
import kotlin.math.sin

class StationaryCameraRouteSpec : FunSpec({
    val world = World("worlds_lobby")
    val start = Position(world, 35.0, 84.6, 28.0)
    val end = Position(world, 39.0, 84.6, 34.0)
    val target = Position(world, 40.0, 83.6, 29.0)
    val route = StationaryCameraRoute(start, end, target, 40, 140)

    test("baige shot moves for two seconds and holds the endpoint for five seconds") {
        route.position(0).x shouldBe start.x
        route.position(20).x shouldBe 37.0
        route.position(20).z shouldBe 31.0
        for (frame in 40..140) {
            route.position(frame).x shouldBe end.x
            route.position(frame).y shouldBe end.y
            route.position(frame).z shouldBe end.z
        }
        route.position(-1).x shouldBe start.x
        route.position(10000).x shouldBe end.x
    }

    test("camera rotation points at the head throughout the route including the endpoint") {
        for (frame in 0..140) {
            val pos = route.position(frame)
            val yaw = Math.toRadians(pos.yaw.toDouble())
            val pitch = Math.toRadians(pos.pitch.toDouble())
            val dx = target.x - pos.x
            val dy = target.y - pos.y
            val dz = target.z - pos.z
            val length = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
            -sin(yaw) * cos(pitch) shouldBe (dx / length plusOrMinus 0.00001)
            -sin(pitch) shouldBe (dy / length plusOrMinus 0.00001)
            cos(yaw) * cos(pitch) shouldBe (dz / length plusOrMinus 0.00001)
        }
    }

    test("sent chunk check covers the whole route and target with a border") {
        route.requiredChunks() shouldBe buildSet {
            for (x in 1..3) for (z in 0..3) add(StationaryCameraRoute.chunkKey(x, z))
        }
    }

    test("negative coordinates use floor chunk indexing and Bukkit chunk key layout") {
        StationaryCameraRoute.chunkKey(-1, -2) shouldBe -4294967297L
        val point = Position(world, -0.01, 80.0, -16.01)
        val chunks = StationaryCameraRoute(point, point, point, 1, 1).requiredChunks()
        chunks shouldBe buildSet {
            for (x in -2..0) for (z in -3..-1) add(StationaryCameraRoute.chunkKey(x, z))
        }
    }

    test("remote or cross-world camera shots are rejected before any chunk iteration") {
        route.withinRange(start.copy(x = 200.0), 96.0) shouldBe false
        route.withinRange(start.copy(world = World("other")), 96.0) shouldBe false
        route.withinRange(start, Double.NaN) shouldBe false
        route.withinRange(start, 257.0) shouldBe false
        route.withinRange(start, 96.0) shouldBe true
        val remote = start.copy(x = 29_000_000.0)
        shouldThrow<IllegalArgumentException> { StationaryCameraRoute(start, remote, target, 40, 140).requiredChunks() }
    }

    test("invalid geometry and duration fail instead of emitting corrupt packets or unbounded work") {
        shouldThrow<IllegalArgumentException> { StationaryCameraRoute(start, end.copy(x = Double.NaN), target, 40, 140) }
        shouldThrow<IllegalArgumentException> { StationaryCameraRoute(start, end.copy(world = World("other")), target, 40, 140) }
        shouldThrow<IllegalArgumentException> { StationaryCameraRoute(start, end, target, 0, 140) }
        shouldThrow<IllegalArgumentException> { StationaryCameraRoute(start, end, target, 40, 30) }
        shouldThrow<IllegalArgumentException> { StationaryCameraRoute(start, end, target, 40, 1201) }
    }

    test("wardrobe shot starts north and ends west with a one second endpoint hold") {
        val first = Position(world, 40.0, 92.6, 7.0, yaw = 180f, pitch = 0f)
        val last = Position(world, 38.0, 94.6, -3.0, yaw = 90f, pitch = 0f)
        val shot = StationaryCameraRoute(first, last, target, 40, 60, true)
        shot.position(0) shouldBe first
        shot.position(20).yaw shouldBe 135f
        shot.position(20).x shouldBe 39.0
        shot.position(20).y shouldBe 93.6
        for (frame in 40..60) shot.position(frame) shouldBe last
    }

    test("fixed heading uses the short turn across the yaw boundary") {
        val shot = StationaryCameraRoute(start.copy(yaw = 170f), end.copy(yaw = -170f), target, 40, 60, true)
        shot.position(20).yaw shouldBe 180f
        shot.position(40).yaw shouldBe 190f
    }

    test("non-finite heading and impossible pitch are rejected") {
        shouldThrow<IllegalArgumentException> {
            StationaryCameraRoute(start.copy(yaw = Float.NaN), end, target, 40, 60, true)
        }
        shouldThrow<IllegalArgumentException> {
            StationaryCameraRoute(start, end.copy(pitch = 91f), target, 40, 60, true)
        }
    }
})
