package com.typewritermc.utils

import com.typewritermc.engine.paper.utils.ConcurrentYamlFile
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.*
import org.bukkit.configuration.InvalidConfigurationException
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ConcurrentYamlFileSpec : FunSpec({
    lateinit var directory: File
    val files = mutableListOf<ConcurrentYamlFile>()
    beforeTest { directory = Files.createTempDirectory("typewriter-yaml-test-").toFile() }
    afterTest {
        files.forEach { runCatching { it.close() } }
        files.clear()
        directory.deleteRecursively()
    }

    fun store(file: File) = ConcurrentYamlFile(file, { throw AssertionError("Unexpected save failure", it) })
        .also(files::add)

    test("parallel default registration preserves existing values and every new key through close") {
        val file = File(directory, "config.yml").apply { writeText("existing: 7\n") }
        val store = store(file)
        store.load()
        coroutineScope {
            repeat(200) { index -> launch(Dispatchers.Default) {
                store.getOrDefault("parallel.key$index", index, "中文配置说明") shouldBe index
                store.getOrDefault("existing", 99) shouldBe 7
            } }
        }
        store.close()
        val saved = YamlConfiguration.loadConfiguration(file)
        saved.getInt("existing") shouldBe 7
        repeat(200) { saved.getInt("parallel.key$it") shouldBe it }
        saved.getComments("parallel.key0") shouldBe listOf("中文配置说明")
    }

    test("region-style memory reads and default registration do not wait for an in-flight file write") {
        val file = File(directory, "config.yml")
        val started = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val store = ConcurrentYamlFile(file, { throw AssertionError(it) }) { target, text ->
            started.complete(Unit)
            check(release.await(5, TimeUnit.SECONDS))
            target.writeText(text)
        }.also(files::add)
        store.load()
        store.getOrDefault("first", 1)
        started.await()
        try {
            withTimeout(2000) {
                withContext(Dispatchers.Default) {
                    store.get("first") shouldBe 1
                    store.getOrDefault("second", 2) shouldBe 2
                }
            }
        } finally {
            release.countDown()
        }
        store.close()
        YamlConfiguration.loadConfiguration(file).getInt("second") shouldBe 2
    }

    test("an invalid external YAML preserves the file and the last usable in-memory values") {
        val file = File(directory, "config.yml").apply { writeText("existing: 7\n") }
        val store = store(file)
        store.load()
        val broken = "existing: [unterminated\n"
        file.writeText(broken)
        shouldThrow<InvalidConfigurationException> { store.load() }
        file.readText() shouldBe broken
        store.get("existing") shouldBe 7
    }

    test("saving a new default merges with external edits without replacing their values") {
        val file = File(directory, "config.yml").apply { writeText("existing: 7\n") }
        val store = store(file)
        store.load()
        file.writeText("existing: 42\nexternal: retained\n")
        store.getOrDefault("new", "default", "默认内容")
        store.flush()
        val saved = YamlConfiguration.loadConfiguration(file)
        saved.getInt("existing") shouldBe 42
        saved.getString("external") shouldBe "retained"
        store.load()
        store.get("existing") shouldBe 42
    }

    test("configuration collections cannot mutate shared values and closed files reject late callbacks") {
        val file = File(directory, "config.yml")
        val store = store(file)
        store.load()
        val original = mutableListOf("first")
        store.registerDefault("list", original)
        original += "external mutation"
        store.get("list") shouldBe listOf("first")
        @Suppress("UNCHECKED_CAST")
        val exposed = store.get("list") as MutableList<String>
        shouldThrow<UnsupportedOperationException> { exposed += "caller mutation" }
        store.close()
        shouldThrow<IllegalStateException> { store.registerDefault("late", 1) }
        YamlConfiguration.loadConfiguration(file).contains("late") shouldBe false
    }
})
