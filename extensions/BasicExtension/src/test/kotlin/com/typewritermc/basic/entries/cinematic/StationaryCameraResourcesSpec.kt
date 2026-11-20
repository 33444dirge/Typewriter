package com.typewritermc.basic.entries.cinematic

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class StationaryCameraResourcesSpec : FunSpec({
    test("reset camera precedes entity deletion even when setup only partially completed") {
        val calls = mutableListOf<String>()
        val lease = StationaryCameraResources { calls.add("error") }
        lease.use {
            remember { calls.add("remove") }
            remember { calls.add("despawn") }
            remember { calls.add("unregister") }
            remember { calls.add("unblock") }
            remember { calls.add("reset-camera") }
        }
        lease.close()
        lease.close()
        calls shouldBe listOf("reset-camera", "unblock", "unregister", "despawn", "remove")
    }

    test("a failing network reset still releases interceptors listeners and the virtual entity") {
        val remaining = AtomicInteger(3)
        val errors = AtomicInteger()
        val lease = StationaryCameraResources { errors.incrementAndGet() }
        lease.use {
            repeat(3) { remember { remaining.decrementAndGet() } }
            remember { throw IllegalStateException("disconnected") }
        }
        lease.close()
        lease.closed shouldBe true
        remaining.get() shouldBe 0
        errors.get() shouldBe 1
    }

    test("retirement waits only for local acquisition and cannot leak a newly registered interceptor") {
        val acquired = CountDownLatch(1)
        val allow = CountDownLatch(1)
        val released = AtomicInteger()
        val lease = StationaryCameraResources { throw it }
        val owner = thread {
            lease.use {
                remember { released.incrementAndGet() }
                acquired.countDown()
                check(allow.await(2, TimeUnit.SECONDS))
                remember { released.incrementAndGet() }
            }
        }
        acquired.await(2, TimeUnit.SECONDS) shouldBe true
        val retiring = thread { lease.close() }
        allow.countDown()
        owner.join(2000)
        retiring.join(2000)
        owner.isAlive shouldBe false
        retiring.isAlive shouldBe false
        released.get() shouldBe 2
        lease.use { error("Retired camera must not send another camera packet") }
    }

    test("racing stop quit disable and normal end clean the scene once") {
        val released = AtomicInteger()
        val lease = StationaryCameraResources { throw it }
        lease.use { remember { released.incrementAndGet() } }
        val threads = List(8) { thread { repeat(50) { lease.close() } } }
        threads.forEach { it.join(2000); it.isAlive shouldBe false }
        released.get() shouldBe 1
    }
})
