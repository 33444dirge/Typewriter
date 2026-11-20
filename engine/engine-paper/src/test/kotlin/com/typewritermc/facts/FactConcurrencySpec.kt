package com.typewritermc.facts

import com.typewritermc.engine.paper.entry.entries.GroupId
import com.typewritermc.engine.paper.facts.FactData
import com.typewritermc.engine.paper.facts.FactDatabase
import com.typewritermc.engine.paper.facts.FactId
import com.typewritermc.engine.paper.facts.storage.readFactFile
import com.typewritermc.engine.paper.facts.storage.writeFactFile
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.time.LocalDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class FactConcurrencySpec : FunSpec({
    test("players in different regions do not lose updates to their shared group fact") {
        val database = FactDatabase()
        val id = FactId("quest", GroupId("party"))
        val gate = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(8)
        val completions = AtomicInteger()
        try {
            val results = (1..8).map {
                workers.submit {
                    gate.await()
                    repeat(2000) {
                        val change = database.update(id) { FactData(it.value + 1) }
                        if (change.before.value < 100 && change.after.value >= 100) completions.incrementAndGet()
                    }
                }
            }
            gate.countDown()
            results.forEach { it.get(10, TimeUnit.SECONDS) }
            database[id]?.value shouldBe 16000
            completions.get() shouldBe 1
        } finally {
            workers.shutdownNow()
        }
    }

    test("a saved fact keeps every player group under one entry") {
        val folder = Files.createTempDirectory("typewriter-facts")
        val file = folder.resolve("facts.json").toFile()
        try {
            val timestamp = LocalDateTime.of(2026, 11, 7, 12, 0)
            val facts = listOf(
                FactId("quest", GroupId("first")) to FactData(3, timestamp),
                FactId("quest", GroupId("second")) to FactData(8, timestamp),
                FactId("other", GroupId("first")) to FactData(2, timestamp),
            )
            writeFactFile(file, facts)
            readFactFile(file) shouldBe facts.toMap()
            file.readText().windowed("\"quest\"".length).count { it == "\"quest\"" } shouldBe 1
        } finally {
            Files.deleteIfExists(file.toPath())
            Files.deleteIfExists(folder)
        }
    }

    test("clearing the final fact also clears the persistent snapshot") {
        val folder = Files.createTempDirectory("typewriter-facts")
        val file = folder.resolve("facts.json").toFile()
        try {
            writeFactFile(file, listOf(FactId("quest", GroupId("party")) to FactData(7)))
            writeFactFile(file, emptyList())
            readFactFile(file) shouldBe emptyMap()
            folder.toFile().listFiles()!!.map { it.name } shouldBe listOf("facts.json")
        } finally {
            Files.deleteIfExists(file.toPath())
            Files.deleteIfExists(folder)
        }
    }
})
