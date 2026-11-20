package com.typewritermc.entity

import com.typewritermc.engine.paper.interaction.PendingEventBuffer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class PendingEventBoundsSpec : FunSpec({
    test("a trigger flood retains only the accepted batch in original order") {
        val buffer = PendingEventBuffer<Int>(4096)
        var accepted = 0
        repeat(100_000) { if (buffer.offer(it)) accepted++ }
        accepted shouldBe 4096
        buffer.drain() shouldBe (0 until 4096).toList()
        buffer.drain().isEmpty() shouldBe true
        buffer.offer(100_001) shouldBe true
        buffer.drain() shouldBe listOf(100_001)
    }

    test("retirement releases queued events and restores all capacity") {
        val buffer = PendingEventBuffer<String>(1)
        buffer.offer("previous") shouldBe true
        buffer.offer("rejected") shouldBe false
        buffer.clear()
        buffer.offer("new") shouldBe true
        buffer.drain() shouldBe listOf("new")
    }
})
