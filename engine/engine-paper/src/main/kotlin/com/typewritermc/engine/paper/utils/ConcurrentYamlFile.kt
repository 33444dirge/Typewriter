package com.typewritermc.engine.paper.utils

import com.typewritermc.engine.paper.logger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.inventory.ItemStack
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.*
import java.util.Collections
import java.util.logging.Level

/** Keeps region callbacks in memory and serializes file access on an explicitly closed I/O worker. */
class ConcurrentYamlFile(
    private val file: File,
    private val reportFailure: (Throwable) -> Unit = {
        logger.log(Level.SEVERE, "Failed to save Typewriter configuration ${file.name}", it)
    },
    private val writeFile: (File, String) -> Unit = ::writeYamlAtomically,
) {
    private data class Definition(val value: Any, val comments: List<String>)
    private data class Snapshot(val revision: Long, val definitions: Map<String, Definition>)
    private val stateLock = Any()
    private val persistence = Mutex()
    private val definitions = linkedMapOf<String, Definition>()
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("TypewriterYaml-${file.name}"))
    private var configuration: YamlConfiguration? = null
    private var revision = 0L
    private var savedRevision = 0L
    private var closed = false
    private var worker: Job? = null

    suspend fun load() = withContext(Dispatchers.IO) {
        persistence.withLock {
            val loaded = YamlConfiguration()
            if (file.exists()) loaded.load(file)
            synchronized(stateLock) {
                check(!closed) { "Configuration ${file.name} is closed" }
                var changed = false
                definitions.forEach { (path, definition) ->
                    if (!loaded.contains(path)) {
                        loaded.set(path, definition.value)
                        loaded.setComments(path, definition.comments)
                        changed = true
                    }
                }
                configuration = loaded
                revision++
                savedRevision = if (changed) revision - 1 else revision
                if (worker == null) worker = scope.launch { saveRequests() }
                if (changed) requests.trySend(Unit)
            }
        }
    }

    fun get(path: String): Any? = synchronized(stateLock) {
        requireConfiguration().get(path)?.let(::copyYamlValue)
    }

    fun getOrDefault(path: String, default: Any, comment: String? = null): Any {
        registerDefault(path, default, comment)
        return get(path) ?: copyYamlValue(default)
    }

    fun registerDefault(path: String, default: Any, comment: String? = null) {
        val changed = synchronized(stateLock) {
            val current = requireConfiguration()
            val definition = definitions.getOrPut(path) {
                Definition(copyYamlValue(default), comment?.lines() ?: emptyList())
            }
            if (current.contains(path)) return@synchronized false
            current.set(path, definition.value)
            if (definition.comments.isNotEmpty()) current.setComments(path, definition.comments)
            revision++
            true
        }
        if (changed) requests.trySend(Unit)
    }

    suspend fun flush() = withContext(Dispatchers.IO) { persistence.withLock { persistLatest() } }

    suspend fun close(): Unit = withContext(NonCancellable) {
        val previous = synchronized(stateLock) {
            if (closed) return@withContext
            closed = true
            requests.close()
            worker
        }
        try {
            previous?.join()
            flush()
        } finally {
            scope.cancel()
            synchronized(stateLock) {
                configuration = null
                definitions.clear()
                worker = null
            }
        }
    }

    private fun requireConfiguration(): YamlConfiguration {
        check(!closed) { "Configuration ${file.name} is closed" }
        return checkNotNull(configuration) { "Configuration ${file.name} has not been loaded" }
    }

    private suspend fun saveRequests() {
        for (request in requests) {
            try {
                flush()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                reportFailure(error)
            }
        }
    }

    private fun persistLatest() {
        val snapshot = synchronized(stateLock) {
            if (configuration == null || revision == savedRevision) return
            Snapshot(revision, definitions.toMap())
        }
        val output = YamlConfiguration()
        val existed = file.exists()
        if (existed) output.load(file)
        var changed = !existed
        snapshot.definitions.forEach { (path, definition) ->
            if (!output.contains(path)) {
                output.set(path, definition.value)
                if (definition.comments.isNotEmpty()) output.setComments(path, definition.comments)
                changed = true
            }
        }
        if (changed) writeFile(file, output.saveToString())
        synchronized(stateLock) { savedRevision = snapshot.revision }
    }
}

private fun copyYamlValue(value: Any): Any = when (value) {
    is ConfigurationSection -> copyYamlValue(value.getValues(false))
    is List<*> -> Collections.unmodifiableList(value.map { it?.let(::copyYamlValue) })
    is Map<*, *> -> Collections.unmodifiableMap(value.mapValues { (_, item) -> item?.let(::copyYamlValue) })
    is Set<*> -> Collections.unmodifiableSet(value.map { it?.let(::copyYamlValue) }.toSet())
    is ItemStack -> value.clone()
    else -> value
}

private fun writeYamlAtomically(file: File, text: String) {
    val target = file.toPath().toAbsolutePath()
    Files.createDirectories(target.parent)
    val temporary = Files.createTempFile(target.parent, "${file.name}-", ".tmp")
    try {
        Files.writeString(temporary, text)
        try {
            Files.move(temporary, target, ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, target, REPLACE_EXISTING)
        }
    } finally {
        Files.deleteIfExists(temporary)
    }
}
