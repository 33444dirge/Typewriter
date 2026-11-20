package com.typewritermc.roadnetwork

import com.typewritermc.core.entries.emptyRef
import com.typewritermc.core.utils.UntickedAsync
import com.typewritermc.core.utils.point.Position
import com.typewritermc.roadnetwork.entries.*
import com.typewritermc.engine.paper.utils.FoliaScheduler
import com.typewritermc.engine.paper.utils.foliaScheduler
import com.typewritermc.engine.paper.snippets.SnippetDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrow
import io.mockk.*
import kotlinx.coroutines.*
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.time.Duration
import kotlin.reflect.KClass

private class PathStreamSnippets : SnippetDatabase {
    override fun get(path: String, default: Any, comment: String): Any = default
    override fun <T : Any> getSnippet(path: String, klass: KClass<T>, default: T, comment: String): T = default
    override fun registerSnippet(path: String, defaultValue: Any, comment: String) {}
}

class PathStreamOwnerSpec : FunSpec({
    val player = mockk<Player>()
    val scheduler = mockk<FoliaScheduler>()
    var onOwner = true
    val queued = mutableListOf<Runnable>()
    val retired = mutableListOf<Runnable>()
    lateinit var calculation: CompletableDeferred<Unit>
    var constructions = 0
    var displays = 0
    var disposals = 0
    var resourceDisposals = 0
    var displayRetirements = 0

    fun producer() = object : PathStreamProducer(
        player, "test", emptyRef(), { Position.ORIGIN }, { Position.ORIGIN }, Duration.ofHours(1), { emptyList() },
    ) {
        override suspend fun refreshPath(): PathStream {
            withContext(Dispatchers.UntickedAsync) { calculation.await() }
            onOwner shouldBe true
            constructions++
            return object : PathStream(listOf(object : PathStreamDisplay {
                override fun display(position: Position) { onOwner shouldBe true; displays++ }
                override fun dispose() { onOwner shouldBe true; disposals++ }
                override fun retireResources() { displayRetirements++ }
            })) {
                override fun forwardPath() = Position.ORIGIN
                override fun shouldContinue() = true
                override fun disposeResources() { resourceDisposals++ }
            }
        }
    }

    beforeTest {
        startKoin { modules(module { single<SnippetDatabase> { PathStreamSnippets() } }) }
        onOwner = true
        queued.clear()
        retired.clear()
        calculation = CompletableDeferred()
        constructions = 0
        displays = 0
        disposals = 0
        resourceDisposals = 0
        displayRetirements = 0
        mockkStatic(Bukkit::class)
        mockkStatic("com.typewritermc.engine.paper.utils.PaperCoroutineKt")
        mockkStatic("com.typewritermc.core.utils.CachedThreadPoolDispatcherKt")
        every { Dispatchers.UntickedAsync } returns Dispatchers.Unconfined
        every { foliaScheduler } returns scheduler
        every { scheduler.active() } returns true
        every { scheduler.owns(player) } answers { onOwner }
        every { Bukkit.isOwnedByCurrentRegion(player) } answers { onOwner }
        every { scheduler.atEntity(player, any(), any(), 1L) } answers {
            queued += secondArg<Runnable>()
            thirdArg<Runnable?>()?.let(retired::add)
            mockk(relaxed = true)
        }
    }
    afterTest {
        calculation.cancel()
        unmockkStatic("com.typewritermc.core.utils.CachedThreadPoolDispatcherKt")
        unmockkStatic("com.typewritermc.engine.paper.utils.PaperCoroutineKt")
        unmockkStatic(Bukkit::class)
        stopKoin()
    }

    test("an asynchronous path builds and displays only after returning to the player owner") {
        val producer = producer()
        producer.tick()
        onOwner = false
        calculation.complete(Unit)
        constructions shouldBe 0
        queued.size shouldBe 1
        onOwner = true
        queued.removeFirst().run()
        constructions shouldBe 1
        displays shouldBe 0
        producer.tick()
        displays shouldBe 1
        producer.dispose()
        producer.dispose()
        disposals shouldBe 1
        resourceDisposals shouldBe 1
    }

    test("retirement cancels calculation resources without disposing live displays on the wrong owner") {
        calculation.complete(Unit)
        val producer = producer()
        producer.tick()
        constructions shouldBe 1
        onOwner = false
        producer.dispose()
        retired.single().run()
        resourceDisposals shouldBe 1
        disposals shouldBe 0
        displayRetirements shouldBe 1
        onOwner = true
        queued.single().run()
        producer.tick()
        resourceDisposals shouldBe 1
        displays shouldBe 1
    }

    test("a failed display cleanup does not retain subsequent displays or path calculation resources") {
        val failure = IllegalStateException("display cleanup failed")
        val stream = object : PathStream(listOf(
            object : PathStreamDisplay {
                override fun display(position: Position) {}
                override fun dispose() { throw failure }
            },
            object : PathStreamDisplay {
                override fun display(position: Position) {}
                override fun dispose() { disposals++ }
            },
        )) {
            override fun forwardPath() = Position.ORIGIN
            override fun shouldContinue() = false
            override fun disposeResources() { resourceDisposals++ }
        }
        shouldThrow<IllegalStateException> { stream.dispose() } shouldBe failure
        disposals shouldBe 1
        resourceDisposals shouldBe 1
    }
})
