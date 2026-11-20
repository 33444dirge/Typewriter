package com.typewritermc.utils

import com.typewritermc.core.utils.TypewriterCoroutineLifetime
import com.typewritermc.core.utils.launch
import com.typewritermc.engine.paper.utils.FoliaScheduler
import com.typewritermc.engine.paper.utils.Sync
import com.typewritermc.engine.paper.utils.foliaScheduler
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.*
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import kotlinx.coroutines.*
import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
import java.util.function.Consumer

class StoppedSchedulerShutdownSpec : FunSpec({
    test("closing releases a continuation queued before global ticks stop so shutdown can join its job") {
        val plugin = mockk<Plugin>()
        val global = mockk<GlobalRegionScheduler>(relaxed = true)
        val task = mockk<ScheduledTask>(relaxed = true)
        val callback = slot<Consumer<ScheduledTask>>()
        var enabled = true
        mockkStatic(Bukkit::class)
        mockkStatic("com.typewritermc.engine.paper.utils.PaperCoroutineKt")
        every { plugin.isEnabled } answers { enabled }
        every { Bukkit.isGlobalTickThread() } returns false
        every { Bukkit.getGlobalRegionScheduler() } returns global
        every { Bukkit.getAsyncScheduler() } returns mockk(relaxed = true)
        every { global.runDelayed(plugin, capture(callback), any()) } returns task
        val wrapper = FoliaScheduler(plugin)
        every { foliaScheduler } returns wrapper
        TypewriterCoroutineLifetime.attachPlatform(SupervisorJob())
        TypewriterCoroutineLifetime.beginGeneration()
        try {
            var ran = false
            val queued = Dispatchers.Sync.launch { ran = true }
            enabled = false
            wrapper.close()
            withTimeout(2000) { TypewriterCoroutineLifetime.stopGeneration(); queued.join() }
            queued.isCancelled shouldBe true
            ran shouldBe false
            callback.captured.accept(task)
            ran shouldBe false
        } finally {
            wrapper.close()
            TypewriterCoroutineLifetime.stopPlatform()
            unmockkStatic("com.typewritermc.engine.paper.utils.PaperCoroutineKt")
            unmockkStatic(Bukkit::class)
        }
    }
})
