package com.typewritermc.utils

import com.typewritermc.core.utils.TypewriterCoroutineLifetime
import com.typewritermc.core.utils.launch
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrow
import kotlinx.coroutines.*

class CoroutineLifetimeSpec : FunSpec({
    lateinit var platform: CompletableJob
    beforeEach {
        platform = SupervisorJob()
        TypewriterCoroutineLifetime.attachPlatform(platform)
        TypewriterCoroutineLifetime.beginGeneration()
    }
    afterEach { TypewriterCoroutineLifetime.stopPlatform() }

    test("unloading cancels an old delayed action while keeping platform services alive") {
        val ready = CompletableDeferred<Unit>()
        var fired = false
        val job = Dispatchers.Unconfined.launch {
            ready.complete(Unit)
            delay(Long.MAX_VALUE)
            fired = true
        }
        ready.await()
        TypewriterCoroutineLifetime.stopGeneration()
        job.isCancelled shouldBe true
        fired shouldBe false
        platform.isActive shouldBe true
    }

    test("reload can finish when called by a job in the generation it unloads") {
        val reloaded = CompletableDeferred<Unit>()
        val original = TypewriterCoroutineLifetime.parent!!
        val job = Dispatchers.Unconfined.launch {
            TypewriterCoroutineLifetime.stopGeneration()
            TypewriterCoroutineLifetime.beginGeneration()
            reloaded.complete(Unit)
        }
        withTimeout(2000) { reloaded.await(); job.join() }
        original.isCancelled shouldBe true
        TypewriterCoroutineLifetime.parent!!.isActive shouldBe true
    }

    test("work cannot start in the gap between unload and the next load") {
        TypewriterCoroutineLifetime.stopGeneration()
        var ran = false
        val job = Dispatchers.Unconfined.launch { ran = true }
        job.join()
        ran shouldBe false
        job.isCancelled shouldBe true
    }

    test("platform shutdown prevents a concurrent reload from opening a new generation") {
        TypewriterCoroutineLifetime.stopPlatform()
        shouldThrow<IllegalStateException> { TypewriterCoroutineLifetime.beginGeneration() }
        platform.isCancelled shouldBe true
        TypewriterCoroutineLifetime.parent!!.isCancelled shouldBe true
    }
})
