package com.typewritermc.entity

import com.google.gson.JsonObject
import com.typewritermc.engine.paper.entry.StagedPages
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.*

class StagedPagesSpec : FunSpec({
    test("loaded input and returned snapshots cannot modify the stored document") {
        val document = StagedPages()
        val input = JsonObject().apply { addProperty("value", 1) }
        document.load(mapOf("page" to input))
        input.addProperty("value", 2)
        document.snapshot().getValue("page").addProperty("value", 3)
        document.snapshot().getValue("page")["value"].asInt shouldBe 1
    }

    test("concurrent edits to the same JSON object retain every increment") {
        val document = StagedPages()
        document.load(mapOf("page" to JsonObject().apply { addProperty("counter", 0) }))
        coroutineScope {
            List(8) { async(Dispatchers.Default) {
                repeat(100) {
                    document.edit { pages ->
                        val page = pages.getValue("page")
                        page.addProperty("counter", page["counter"].asInt + 1)
                        Result.success("updated")
                    }.isSuccess shouldBe true
                }
            } }.awaitAll()
        }
        document.snapshot().getValue("page")["counter"].asInt shouldBe 800
    }

    test("publishing freezes edits until completion and returns an independent snapshot") {
        val document = StagedPages()
        document.load(mapOf("page" to JsonObject()))
        val published = document.beginPublish()!!
        document.beginPublish() shouldBe null
        document.edit { it.clear(); Result.success("deleted") }.isFailure shouldBe true
        published.getValue("page").addProperty("outside", true)
        document.snapshot().getValue("page").has("outside") shouldBe false
        document.endPublish()
        document.edit { it.clear(); Result.success("deleted") }.isSuccess shouldBe true
        document.snapshotOrNull() shouldBe emptyMap()
    }

    test("an unloaded document is distinct from an intentionally emptied document") {
        val document = StagedPages()
        document.snapshotOrNull() shouldBe null
        document.edit { Result.success("invalid") }.isFailure shouldBe true
        document.load(emptyMap())
        document.snapshotOrNull() shouldBe emptyMap()
    }
})
