package city.jiujiu.typewriter

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.mockk.verify
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.CompletableFuture

class StorySessionSpec : StringSpec({
    "reload cancels only owned handles and rejects stale callbacks" {
        val player = mockk<Player>()
        val handle = mockk<ScheduledTask>(relaxed = true)
        var callback: Runnable? = null
        var runs = 0
        val session = StorySession({ false }) { _, action, _ -> callback = action; handle }
        session.atPlayer(player) { runs++ }
        session.close()
        callback!!.run()
        session.atPlayer(player) { runs++ }
        runs shouldBe 0
        verify(exactly = 1) { handle.cancel() }
    }
    "a finished callback is released instead of retained until reload" {
        val handle = mockk<ScheduledTask>(relaxed = true)
        val session = StorySession({ false }) { _, action, _ -> action.run(); handle }
        session.atPlayer(mockk()) { }
        session.close()
        verify(exactly = 0) { handle.cancel() }
    }
    "permission writes are bounded deduplicated and canceled on shutdown" {
        val session = StorySession({ true }) { _, _, _ -> null }
        val first = UUID.randomUUID()
        val future = CompletableFuture<Void>()
        session.save(first) { future } shouldBe future
        session.save(first) { error("duplicate must not begin") } shouldBe null
        repeat(127) { session.save(UUID.randomUUID()) { CompletableFuture<Void>() } }
        session.save(UUID.randomUUID()) { error("overflow must not begin") } shouldBe null
        future.complete(null)
        val next = CompletableFuture<Void>()
        session.save(first) { next } shouldBe next
        session.close()
        next.isCancelled shouldBe true
        session.save(first) { error("closed session") } shouldBe null
    }
})
