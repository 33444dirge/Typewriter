package com.typewritermc.utils

import com.typewritermc.engine.paper.utils.FoliaScheduler
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrow
import io.mockk.*
import io.papermc.paper.threadedregions.scheduler.EntityScheduler
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler
import io.papermc.paper.threadedregions.scheduler.AsyncScheduler
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Entity
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.IllegalPluginAccessException
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer

class FoliaSchedulerSpec : FunSpec({
    val plugin = mockk<Plugin>()
    val entity = mockk<Entity>()
    val scheduler = mockk<EntityScheduler>()
    val globalScheduler = mockk<GlobalRegionScheduler>(relaxed = true)
    val asyncScheduler = mockk<AsyncScheduler>(relaxed = true)
    val task = mockk<ScheduledTask>(relaxed = true)
    val execute = slot<Consumer<ScheduledTask>>()
    val retired = slot<Runnable>()
    lateinit var wrapper: FoliaScheduler

    beforeEach {
        clearMocks(plugin, entity, scheduler, globalScheduler, asyncScheduler, task)
        mockkStatic(Bukkit::class)
        every { plugin.isEnabled } returns true
        every { plugin.logger } returns java.util.logging.Logger.getLogger("TypewriterSchedulerTest")
        every { entity.scheduler } returns scheduler
        every { task.cancel() } returns ScheduledTask.CancelledState.CANCELLED_BY_CALLER
        every { Bukkit.getGlobalRegionScheduler() } returns globalScheduler
        every { Bukkit.getRegionScheduler() } returns mockk(relaxed = true)
        every { Bukkit.getAsyncScheduler() } returns asyncScheduler
        every { scheduler.runDelayed(plugin, capture(execute), capture(retired), any()) } returns task
        wrapper = FoliaScheduler(plugin)
    }

    afterEach {
        wrapper.close()
        unmockkStatic(Bukkit::class)
    }

    test("entity work always waits at least one tick") {
        var calls = 0
        wrapper.atEntity(entity, { calls++ }, null, 0)
        calls shouldBe 0
        verify { scheduler.runDelayed(plugin, any(), any(), 1L) }
        execute.captured.accept(task)
        calls shouldBe 1
    }

    test("a repeating callback failure releases its pending task once") {
        var cleanups = 0
        every { scheduler.runAtFixedRate(plugin, capture(execute), capture(retired), any(), any()) } returns task
        wrapper.repeatEntity(entity, { error("callback failed") }, { cleanups++ }, 1L, 1L)
        shouldThrow<IllegalStateException> { execute.captured.accept(task) }
        wrapper.close()
        retired.captured.run()
        cleanups shouldBe 1
        verify(exactly = 1) { task.cancel() }
    }

    test("a registration failure releases its continuation without retaining it until shutdown") {
        var cleanups = 0
        every { scheduler.runDelayed(plugin, any(), any(), any()) } throws IllegalArgumentException("invalid region")
        shouldThrow<IllegalArgumentException> { wrapper.atEntity(entity, {}, { cleanups++ }, 1L) }
        cleanups shouldBe 1
        wrapper.close()
        cleanups shouldBe 1
    }

    test("a failing cleanup does not prevent other pending tasks from closing") {
        var cleanups = 0
        wrapper.atEntity(entity, {}, { error("cleanup failed") }, 1L)
        wrapper.atEntity(entity, {}, { cleanups++ }, 1L)
        wrapper.close()
        cleanups shouldBe 1
    }

    test("a scheduler cancellation failure still releases every continuation and closes both schedulers") {
        var cleanups = 0
        every { task.cancel() } throws IllegalStateException("cancel failed")
        every { globalScheduler.cancelTasks(plugin) } throws IllegalStateException("global cancel failed")
        wrapper.atEntity(entity, {}, { cleanups++ }, 1L)
        wrapper.atEntity(entity, {}, { cleanups++ }, 1L)
        wrapper.close()
        cleanups shouldBe 2
        verify(exactly = 1) { asyncScheduler.cancelTasks(plugin) }
        execute.captured.accept(task)
        cleanups shouldBe 2
    }

    test("explicit cancellation failure releases a task once") {
        var cleanups = 0
        val pending = wrapper.atEntity(entity, {}, { cleanups++ }, 1L)!!
        every { task.cancel() } throws IllegalStateException("cancel failed")
        shouldThrow<IllegalStateException> { pending.cancel() }
        wrapper.close()
        cleanups shouldBe 1
        verify(exactly = 1) { task.cancel() }
    }

    test("retirement prevents action and is handled once") {
        var calls = 0
        var cleanups = 0
        wrapper.atEntity(entity, { calls++ }, { cleanups++ }, 1)
        retired.captured.run()
        retired.captured.run()
        execute.captured.accept(task)
        calls shouldBe 0
        cleanups shouldBe 1
    }

    test("an already retired scheduler returning null releases its continuation") {
        every { scheduler.runDelayed(plugin, capture(execute), capture(retired), any()) } returns null
        var cleanups = 0
        wrapper.atEntity(entity, { error("retired work ran") }, { cleanups++ }, 1) shouldBe null
        retired.captured.run()
        cleanups shouldBe 1
    }

    test("shutdown cancels queued entity work and releases it once") {
        var calls = 0
        var cleanups = 0
        wrapper.atEntity(entity, { calls++ }, { cleanups++ }, 1)
        wrapper.close()
        wrapper.close()
        execute.captured.accept(task)
        retired.captured.run()
        calls shouldBe 0
        cleanups shouldBe 1
        verify(exactly = 1) { task.cancel() }
    }

    test("disabled plugins reject without calling the entity scheduler") {
        every { plugin.isEnabled } returns false
        var cleanups = 0
        wrapper.atEntity(entity, { error("disabled work ran") }, { cleanups++ }, 1) shouldBe null
        cleanups shouldBe 1
        verify(exactly = 0) { scheduler.runDelayed(any(), any(), any(), any()) }
    }

    test("teleport waits for the asynchronous result and copies its destination") {
        val destination = Location(mockk<World>(), 10.0, 65.0, -8.0)
        val target = slot<Location>()
        val teleported = CompletableFuture<Boolean>()
        every { entity.teleportAsync(capture(target)) } returns teleported
        val result = wrapper.teleport(entity, destination)
        destination.x = 99.0
        result.isDone shouldBe false
        execute.captured.accept(task)
        target.captured.x shouldBe 10.0
        result.isDone shouldBe false
        teleported.complete(true)
        result.getNow(false) shouldBe true
    }

    test("a retired entity completes its pending teleport with false") {
        val destination = Location(mockk<World>(), 0.0, 64.0, 0.0)
        val result = wrapper.teleport(entity, destination)
        retired.captured.run()
        result.getNow(true) shouldBe false
        verify(exactly = 0) { entity.teleportAsync(any<Location>()) }
    }

    test("repeating tasks clamp both delays and stop after cancellation") {
        every {
            scheduler.runAtFixedRate(plugin, capture(execute), capture(retired), any(), any())
        } returns task
        var calls = 0
        var cleanups = 0
        val repeating = wrapper.repeatEntity(entity, { calls++ }, { cleanups++ }, 0, 0)!!
        verify { scheduler.runAtFixedRate(plugin, any(), any(), 1, 1) }
        execute.captured.accept(task)
        repeating.cancel()
        execute.captured.accept(task)
        retired.captured.run()
        calls shouldBe 1
        cleanups shouldBe 1
    }

    test("closing during global task registration cancels the late task and rejects once") {
        every { globalScheduler.runDelayed(plugin, capture(execute), any()) } answers {
            wrapper.close()
            task
        }
        var calls = 0
        var cleanups = 0
        wrapper.global({ calls++ }, { cleanups++ }, 0)
        execute.captured.accept(task)
        calls shouldBe 0
        cleanups shouldBe 1
        verify { task.cancel() }
    }

    test("async scheduling rejected during disable releases the continuation") {
        every { asyncScheduler.runNow(plugin, any()) } throws
            IllegalPluginAccessException("disabled")
        var cleanups = 0
        wrapper.async({ error("disabled async work ran") }, { cleanups++ }) shouldBe null
        wrapper.close()
        cleanups shouldBe 1
    }

    test("closing cancels queued asynchronous work and ignores its stale callback") {
        every { asyncScheduler.runNow(plugin, capture(execute)) } returns task
        var calls = 0
        var cleanups = 0
        wrapper.async({ calls++ }, { cleanups++ })
        wrapper.close()
        execute.captured.accept(task)
        calls shouldBe 0
        cleanups shouldBe 1
        verify(exactly = 1) { task.cancel() }
    }
})
