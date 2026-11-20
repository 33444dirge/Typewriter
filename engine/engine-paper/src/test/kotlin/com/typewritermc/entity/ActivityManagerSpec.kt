package com.typewritermc.entity

import com.typewritermc.core.entries.Ref
import com.typewritermc.core.entries.emptyRef
import com.typewritermc.core.utils.point.World
import com.typewritermc.engine.paper.entry.entity.*
import com.typewritermc.engine.paper.entry.entries.EntityInstanceEntry
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.*
import com.typewritermc.engine.paper.utils.FoliaScheduler
import com.typewritermc.engine.paper.utils.foliaScheduler
import org.bukkit.entity.Player

private val managerWorld = World("manager-world")

private class ManagerContext(override val viewers: List<Player> = emptyList()) : ActivityContext {
    override val instanceRef: Ref<out EntityInstanceEntry> = emptyRef()
    override val isViewed: Boolean = false
    override val entityState: EntityState = EntityState()
}

private open class CountingActivity(override var currentPosition: PositionProperty) : GenericEntityActivity {
    var activations = 0
    var deactivations = 0
    var disposals = 0
    var ticks = 0

    override fun activate(context: ActivityContext, position: PositionProperty) {
        currentPosition = position
        activations++
    }

    override fun tick(context: ActivityContext): TickResult {
        ticks++
        return TickResult.CONSUMED
    }

    override fun deactivate(context: ActivityContext) {
        deactivations++
    }

    override fun dispose() {
        disposals++
    }
}

class ActivityManagerSpec : FunSpec({
    val spawn = PositionProperty(managerWorld, 0.0, 64.0, 0.0, 0f, 0f)
    val scheduler = mockk<FoliaScheduler>()
    val player = mockk<Player>()
    var owner = false
    val actions = mutableListOf<Runnable>()
    val rejected = mutableListOf<Runnable>()

    beforeSpec {
        mockkStatic("com.typewritermc.engine.paper.utils.PaperCoroutineKt")
        every { foliaScheduler } returns scheduler
        every { scheduler.owns(player) } answers { owner }
        every { scheduler.atEntity(player, any(), any(), 1L) } answers {
            actions += secondArg<Runnable>()
            thirdArg<Runnable?>()?.let(rejected::add)
            mockk(relaxed = true)
        }
    }
    beforeTest {
        owner = false
        actions.clear()
        rejected.clear()
    }
    afterSpec { unmockkStatic("com.typewritermc.engine.paper.utils.PaperCoroutineKt") }

    test("initializing activates the activity where it already stands") {
        val activity = CountingActivity(spawn)
        val manager = ActivityManager(activity)

        manager.initialize(ManagerContext())

        activity.activations shouldBe 1
        activity.currentPosition shouldBe spawn
    }

    test("initializing twice is refused") {
        val manager = ActivityManager(CountingActivity(spawn))
        manager.initialize(ManagerContext())

        shouldThrow<IllegalStateException> { manager.initialize(ManagerContext()) }
    }

    test("disposing deactivates before it disposes") {
        val activity = CountingActivity(spawn)
        val manager = ActivityManager(activity)
        manager.initialize(ManagerContext())

        manager.dispose(ManagerContext())

        activity.deactivations shouldBe 1
        activity.disposals shouldBe 1
    }

    test("disposing twice only tears down once") {
        val activity = CountingActivity(spawn)
        val manager = ActivityManager(activity)
        manager.initialize(ManagerContext())

        manager.dispose(ManagerContext())
        manager.dispose(ManagerContext())

        activity.deactivations shouldBe 1
        activity.disposals shouldBe 1
    }

    test("ticking before initialize never reaches the activity") {
        val activity = CountingActivity(spawn)
        val manager = ActivityManager(activity) { }

        manager.tick(ManagerContext())

        activity.ticks shouldBe 0
    }

    test("ticking after dispose never reaches the activity") {
        val activity = CountingActivity(spawn)
        val manager = ActivityManager(activity) { }
        manager.initialize(ManagerContext())

        manager.tick(ManagerContext())
        activity.ticks shouldBe 1

        manager.dispose(ManagerContext())
        manager.tick(ManagerContext())

        activity.ticks shouldBe 1
    }

    test("a refused tick is reported once, not every tick") {
        val activity = CountingActivity(spawn)
        val reported = mutableListOf<String>()
        val manager = ActivityManager(activity) { reported += it }

        manager.tick(ManagerContext())
        manager.tick(ManagerContext())
        manager.tick(ManagerContext())

        reported.size shouldBe 1
    }

    test("remote ticks merge on the viewer owner and queued ticks cannot revive a disposed activity") {
        val activity = CountingActivity(spawn)
        val context = ManagerContext(listOf(player))
        val manager = ActivityManager(activity) { }
        manager.initialize(context)
        activity.activations shouldBe 0
        owner = true
        actions.removeFirst().run()
        activity.activations shouldBe 1
        owner = false
        repeat(10) { manager.tick(context) }
        actions.size shouldBe 1
        activity.ticks shouldBe 0
        owner = true
        actions.removeFirst().run()
        activity.ticks shouldBe 1
        owner = false
        manager.tick(context)
        owner = true
        manager.dispose(context)
        actions.removeFirst().run()
        activity.ticks shouldBe 1
        activity.disposals shouldBe 1
    }

    test("retirement during initialization releases resources and rejects a late activation") {
        val activity = CountingActivity(spawn)
        val context = ManagerContext(listOf(player))
        val manager = ActivityManager(activity) { }
        manager.initialize(context)
        rejected.single().run()
        owner = true
        actions.single().run()
        manager.tick(context)
        manager.dispose(context)
        activity.activations shouldBe 0
        activity.deactivations shouldBe 0
        activity.disposals shouldBe 1
    }

    test("a partially failed activation is deactivated and disposed once") {
        val activity = object : CountingActivity(spawn) {
            override fun activate(context: ActivityContext, position: PositionProperty) {
                super.activate(context, position)
                error("activation failed")
            }
        }
        val manager = ActivityManager(activity) { }
        shouldThrow<IllegalStateException> { manager.initialize(ManagerContext()) }
        manager.tick(ManagerContext())
        manager.dispose(ManagerContext())
        activity.deactivations shouldBe 1
        activity.disposals shouldBe 1
        activity.ticks shouldBe 0
    }

    test("retired disposal releases resources without touching live viewer state") {
        val activity = CountingActivity(spawn)
        val context = ManagerContext(listOf(player))
        val manager = ActivityManager(activity)
        owner = true
        manager.initialize(context)
        owner = false
        manager.dispose(context)
        activity.disposals shouldBe 0
        rejected.single().run()
        actions.single().run()
        manager.dispose(context)
        activity.disposals shouldBe 1
        activity.deactivations shouldBe 0
    }

    test("viewer changes arriving before activation reach the active shared activity") {
        val changes = mutableListOf<String>()
        val activity = object : SharedEntityActivity {
            override val currentPosition = spawn
            override fun activate(context: SharedActivityContext, position: PositionProperty) { changes += "activate" }
            override fun tick(context: SharedActivityContext) = TickResult.CONSUMED
            override fun addedViewer(context: SharedActivityContext, viewer: Player) { changes += "add" }
            override fun removedViewer(context: SharedActivityContext, viewer: Player) { changes += "remove" }
        }
        val context = SharedActivityContext(emptyRef(), listOf(player))
        val manager = ActivityManager(activity)
        manager.initialize(context)
        manager.addedViewer(context, player)
        manager.removedViewer(context, player)
        changes shouldBe emptyList()
        owner = true
        actions.single().run()
        changes shouldBe listOf("activate", "add", "remove")
    }
})
