package com.typewritermc.entity.entries.activity

import com.typewritermc.core.entries.emptyRef
import com.typewritermc.core.utils.UntickedAsync
import com.typewritermc.core.utils.point.Position
import com.typewritermc.core.utils.point.World
import com.typewritermc.engine.paper.entry.entity.*
import com.typewritermc.roadnetwork.*
import com.typewritermc.roadnetwork.gps.GPSEdge
import com.typewritermc.roadnetwork.gps.PointToPointGPS
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.*
import kotlinx.coroutines.*
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module

class RandomPatrolActivitySpec : FunSpec({
    val world = World("patrol-test")
    val spawn = PositionProperty(world, 0.0, 64.0, 0.0, 0f, 0f)
    val destination = Position(world, 10.0, 64.0, 0.0)
    val network = RoadNetwork(listOf(RoadNode(RoadNodeId(1), destination, 1.0)))
    val manager = mockk<RoadNetworkManager>()
    val context = SharedActivityContext(emptyRef(), emptyList())
    val replies = mutableListOf<CompletableDeferred<Result<List<GPSEdge>>>>()
    val activities = mutableListOf<RandomPatrolActivity>()
    var activations = 0
    var onOwner = false

    beforeTest {
        activations = 0
        onOwner = false
        replies.clear()
        activities.clear()
        mockkStatic("com.typewritermc.core.utils.CachedThreadPoolDispatcherKt")
        every { Dispatchers.UntickedAsync } returns Dispatchers.Unconfined
        mockkConstructor(PointToPointGPS::class, NavigationActivity::class)
        coEvery { anyConstructed<PointToPointGPS>().findPath() } coAnswers {
            val reply = CompletableDeferred<Result<List<GPSEdge>>>()
            replies += reply
            withContext(NonCancellable) { reply.await() }
        }
        every { anyConstructed<NavigationActivity>().activate(any(), any()) } answers {
            onOwner shouldBe true
            activations++
        }
        every { anyConstructed<NavigationActivity>().currentPosition } returns spawn
        every { anyConstructed<NavigationActivity>().dispose() } just Runs
        every { manager.getNetworkOrNull(any()) } returns network
        startKoin { modules(module { single { manager } }) }
    }
    afterTest {
        activities.forEach { it.dispose() }
        replies.forEach { it.complete(Result.success(emptyList())) }
        stopKoin()
        unmockkConstructor(PointToPointGPS::class, NavigationActivity::class)
        unmockkStatic("com.typewritermc.core.utils.CachedThreadPoolDispatcherKt")
    }

    fun start(): RandomPatrolActivity = RandomPatrolActivity(emptyRef(), 10000.0, spawn).also {
        activities += it
        onOwner = true
        it.activate(context, spawn)
        onOwner = false
    }

    test("finishing an asynchronous route waits for the owner tick before changing activities") {
        val activity = start()
        replies.single().complete(Result.success(emptyList()))
        activations shouldBe 0
        onOwner = true
        activity.tick(context)
        activations shouldBe 1
    }

    test("a cancelled search finishing late cannot replace the new activation") {
        val activity = start()
        onOwner = true
        activity.deactivate(context)
        activity.activate(context, spawn)
        onOwner = false
        replies.size shouldBe 2
        replies.first().complete(Result.success(emptyList()))
        onOwner = true
        activity.tick(context)
        activations shouldBe 0
        onOwner = false
        replies.last().complete(Result.success(emptyList()))
        activations shouldBe 0
        onOwner = true
        activity.tick(context)
        activations shouldBe 1
    }
})
