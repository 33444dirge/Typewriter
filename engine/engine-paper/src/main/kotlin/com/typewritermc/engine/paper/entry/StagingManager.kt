package com.typewritermc.engine.paper.entry

import com.google.gson.*
import com.typewritermc.core.entries.Entry
import com.typewritermc.core.entries.Ref
import com.typewritermc.core.entries.pageId
import com.typewritermc.core.utils.UntickedAsync
import com.typewritermc.core.utils.failure
import com.typewritermc.core.utils.ok
import com.typewritermc.core.utils.onFail
import com.typewritermc.core.utils.switchContext
import com.typewritermc.engine.paper.entry.StagingState.*
import com.typewritermc.engine.paper.events.StagingChangeEvent
import com.typewritermc.engine.paper.logger
import com.typewritermc.engine.paper.plugin
import com.typewritermc.engine.paper.ui.ClientSynchronizer
import com.typewritermc.engine.paper.utils.Timeout
import com.typewritermc.engine.paper.utils.get
import kotlinx.coroutines.Dispatchers
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.qualifier.named
import org.koin.java.KoinJavaComponent
import java.io.File
import java.lang.reflect.Type
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration.Companion.seconds

interface StagingManager {
    val stagingState: StagingState
    val pages: Map<String, JsonObject>

    fun loadState()
    fun unload()
    fun createPage(data: JsonObject): Result<String>
    fun renamePage(pageId: String, newName: String): Result<String>
    fun changePageValue(pageId: String, path: String, value: JsonElement): Result<String>
    fun deletePage(pageId: String): Result<String>
    fun moveEntry(entryId: String, fromPageId: String, toPageId: String): Result<String>
    fun createEntry(pageId: String, data: JsonObject): Result<String>
    fun updateEntryField(pageId: String, entryId: String, path: String, value: JsonElement): Result<String>
    fun updateEntry(pageId: String, data: JsonObject): Result<String>
    fun reorderEntry(pageId: String, entryId: String, newIndex: Int): Result<String>
    fun deleteEntry(pageId: String, entryId: String): Result<String>

    fun findEntryPage(entryId: String): Result<String>
    suspend fun publish(): Result<String>
    fun shutdown()
}

class StagingManagerImpl : StagingManager, KoinComponent {
    private val gson: Gson by inject(named("bukkitDataParser"))

    private val documents = StagedPages()
    private val ioLock = ReentrantLock()
    override val pages: Map<String, JsonObject>
        get() = documents.snapshot()

    private val autoSaver =
        Timeout(
            3.seconds,
            ::saveStaging,
            immediateRunnable = {
                // When called to save, we immediately want to update the staging state
                if (!documents.publishing) stagingState = STAGING
            })

    private val stagingDir
        get() = plugin.dataFolder["staging"]

    private val publishedDir
        get() = plugin.dataFolder["pages"]

    @Volatile override var stagingState = PUBLISHED
        private set(value) {
            field = value
            StagingChangeEvent(value).callEvent()
        }

    override fun loadState() {
        stagingState = if (stagingDir.exists()) STAGING else PUBLISHED

        // Read the pages from the file
        val dir = if (stagingState == STAGING) stagingDir else publishedDir
        documents.load(ioLock.withLock { fetchPages(dir) })
    }

    private fun fetchPages(dir: File): Map<String, JsonObject> {
        val pages = mutableMapOf<String, JsonObject>()
        dir.listFiles()?.filter { it.extension == "json" }?.forEach { file ->
            val page = file.readText()
            val pageId = file.nameWithoutExtension
            val pageJson = gson.fromJson(page, JsonObject::class.java)
            // Always force the id to be the same as the file name
            pageJson.addProperty("id", pageId)
            pages[pageId] = pageJson
        }
        return pages
    }

    override fun unload() {
        autoSaver.force()
    }

    override fun createPage(data: JsonObject): Result<String> = edit { pages ->
        if (!data.has("id")) return@edit failure("Id is required")
        if (!data.has("name")) return@edit failure("Name is required")
        val idJson = data["id"]
        if (!idJson.isJsonPrimitive || !idJson.asJsonPrimitive.isString) return@edit failure("Id must be a string")
        val id = idJson.asString

        if (pages.containsKey(id)) return@edit failure("Page with that id already exists")

        // Add the version of this page to track migrations
        data.addProperty("version", plugin.pluginMeta.version)

        pages[id] = data.deepCopy()
        return@edit ok("Successfully created page with name $id")
    }

    override fun renamePage(pageId: String, newName: String): Result<String> = edit { pages ->
        val page = getPage(pages, pageId) onFail { return@edit it }

        page.addProperty("name", newName)

        return@edit ok("Successfully renamed page from $pageId to $newName")
    }

    override fun changePageValue(pageId: String, path: String, value: JsonElement): Result<String> = edit { pages ->
        val page = getPage(pages, pageId) onFail { return@edit it }

        page.changePathValue(path, value.deepCopy())

        return@edit ok("Successfully updated field")
    }

    override fun deletePage(pageId: String): Result<String> = edit { pages ->
        pages.remove(pageId) ?: return@edit failure("Page does not exist")

        return@edit ok("Successfully deleted page with name $pageId")
    }

    override fun moveEntry(entryId: String, fromPageId: String, toPageId: String): Result<String> = edit { pages ->
        val from = pages[fromPageId] ?: return@edit failure("Page '$fromPageId' does not exist")
        val to = pages[toPageId] ?: return@edit failure("Page '$toPageId' does not exist")

        val entry = from["entries"].asJsonArray.find { it.asJsonObject["id"].asString == entryId }
            ?: return@edit failure("Entry does not exist in page '$fromPageId'")

        from["entries"].asJsonArray.remove(entry)
        to["entries"].asJsonArray.add(entry)

        return@edit ok("Successfully moved entry")
    }

    override fun createEntry(pageId: String, data: JsonObject): Result<String> = edit { pages ->
        val page = getPage(pages, pageId) onFail { return@edit it }
        val entries = page["entries"] as? JsonArray ?: JsonArray()

        entries.add(data.deepCopy())
        page.add("entries", entries)

        return@edit ok("Successfully created entry with id ${data["id"]}")
    }

    override fun updateEntryField(
        pageId: String,
        entryId: String,
        path: String,
        value: JsonElement
    ): Result<String> = edit { pages ->
        // Update the page
        val page = getPage(pages, pageId) onFail { return@edit it }
        val entries = page["entries"].asJsonArray
        val entry = entries.find { it.asJsonObject["id"].asString == entryId } ?: return@edit failure("Entry does not exist")

        // Update the entry
        entry.createPath(path)
        entry.changePathValue(path, value.deepCopy())

        return@edit ok("Successfully updated field")
    }

    override fun updateEntry(pageId: String, data: JsonObject): Result<String> = edit { pages ->
        val page = getPage(pages, pageId) onFail { return@edit it }
        val entries = page["entries"] as? JsonArray ?: return@edit failure("Page does not have any entries")
        val entryId = data["id"]?.asString ?: return@edit failure("Entry does not have an id")

        entries.removeAll { entry -> entry.asJsonObject["id"]?.asString == entryId }
        entries.add(data.deepCopy())

        return@edit ok("Successfully updated entry with id ${data["id"]}")
    }

    override fun reorderEntry(pageId: String, entryId: String, newIndex: Int): Result<String> = edit { pages ->
        val page = getPage(pages, pageId) onFail { return@edit it }
        val entries = page["entries"].asJsonArray
        val oldIndex = entries.indexOfFirst { it.asJsonObject["id"].asString == entryId }

        if (oldIndex == -1) return@edit failure("Entry does not exist")
        if (oldIndex == newIndex) return@edit ok("Entry is already at the correct index")

        val correctIndex = if (oldIndex < newIndex) newIndex - 1 else newIndex

        val entryAtNewIndex = entries[correctIndex]
        entries[correctIndex] = entries[oldIndex]
        entries[oldIndex] = entryAtNewIndex

        return@edit ok("Successfully reordered entry")
    }

    override fun deleteEntry(pageId: String, entryId: String): Result<String> = edit { pages ->
        val page = getPage(pages, pageId) onFail { return@edit it }
        val entries = page["entries"].asJsonArray
        val entry = entries.find { it.asJsonObject["id"].asString == entryId } ?: return@edit failure("Entry does not exist")

        entries.remove(entry)

        return@edit ok("Successfully deleted entry with id $entryId")
    }

    override fun findEntryPage(entryId: String): Result<String> {
        val page = pages.values.find { page ->
            page["entries"].asJsonArray.any { entry -> entry.asJsonObject["id"].asString == entryId }
        } ?: return failure("Entry does not exist")

        return ok(page["name"].asString)
    }

    private fun getPage(pages: Map<String, JsonObject>, id: String): Result<JsonObject> {
        val page = pages[id] ?: return failure("Page '$id' does not exist")
        return ok(page)
    }

    private fun edit(action: (MutableMap<String, JsonObject>) -> Result<String>): Result<String> {
        val result = documents.edit(action)
        if (result.isSuccess) autoSaver()
        return result
    }

    private fun saveStaging() = ioLock.withLock {
        // If we are already publishing, we don't want to save the staging
        if (documents.publishing) return@withLock
        // Data is not loaded yet, so we can't save it
        val pages = documents.snapshotOrNull() ?: return@withLock
        val dir = stagingDir
        dir.mkdirs()

        pages.forEach { (id, page) ->
            val file = dir["$id.json"]
            if (!file.exists()) {
                file.parentFile.mkdirs()
                file.createNewFile()
            }
            file.writeText(page.toString())
        }

        dir.listFiles()?.filter { it.nameWithoutExtension !in pages.keys }?.forEach { it.delete() }

        stagingState = STAGING
    }

    // Save the page to the file
    override suspend fun publish(): Result<String> {
        if (stagingState != STAGING) return failure("Can only publish when in staging")
        val pages = documents.beginPublish() ?: return failure("Pages are not loaded or already publishing")
        stagingState = PUBLISHING
        autoSaver.cancel()
        return try {
            Dispatchers.IO.switchContext {
                try {
                    ioLock.withLock {
                        publishedDir.mkdirs()
                        pages.forEach { (name, page) ->
                            publishedDir["$name.json"].writeText(page.toString())
                        }
                        val deletedPages = publishedDir.listFiles().orEmpty()
                            .filter { it.nameWithoutExtension !in pages.keys }
                        if (deletedPages.isNotEmpty()) {
                            logger.info("Deleting ${deletedPages.size} pages, as they are no longer in staging. " +
                                "(${deletedPages.joinToString(", ") { it.nameWithoutExtension }})")
                            deletedPages.backupAndMove()
                        }
                        stagingDir.deleteRecursively()
                    }
                    plugin.reload()
                    stagingState = PUBLISHED
                    ok("Successfully published the staging state")
                } catch (e: Exception) {
                    e.printStackTrace()
                    stagingState = STAGING
                    failure("Failed to publish the staging state")
                }
            }
        } finally {
            documents.endPublish()
            if (stagingState == PUBLISHING) stagingState = STAGING
        }
    }

    override fun shutdown() {
        autoSaver.cancel()
        if (stagingState == STAGING) saveStaging()
    }
}

fun JsonElement.changePathValue(path: String, value: JsonElement) {
    val pathParts = path.split(".")
    var current: JsonElement = this
    pathParts.forEachIndexed { index, key ->
        if (index == pathParts.size - 1) {
            if (current.isJsonObject) {
                current.asJsonObject.add(key, value)
            } else if (current.isJsonArray) {
                val i = Integer.parseInt(key)
                while (current.asJsonArray.size() <= i) {
                    current.asJsonArray.add(JsonNull.INSTANCE)
                }
                current.asJsonArray[i] = value
            }
        } else if (current.isJsonObject) {
            if (!current.asJsonObject.has(key)) {
                current.asJsonObject.add(key, JsonObject())
            }
            current = current.asJsonObject[key]
        } else if (current.isJsonArray) {
            val i = Integer.parseInt(key)
            while (current.asJsonArray.size() <= i) {
                current.asJsonArray.add(JsonObject())
            }
            current = current.asJsonArray[i]
        }
    }
}

private fun JsonElement.createPath(path: String) {
    if (path.contains("*")) return
    val parts = path.split(".")

    fun putIfAbsent(current: JsonElement, path: String, value: JsonElement): JsonElement {
        if (current.isJsonArray && path.toIntOrNull() != null) {
            val index = path.toInt()
            val array = current.asJsonArray

            if (array.size() > index) {
                val cur = array[index]
                // If the current value is null, we see it as an absent value
                if (cur.isJsonNull) {
                    array.set(index, value)
                    return value
                }
                return cur
            } else {
                while (current.asJsonArray.size() <= index) {
                    current.asJsonArray.add(JsonNull.INSTANCE)
                }
                array.set(index, value)
                return value
            }
        } else {
            val obj = current.asJsonObject
            if (obj.has(path) && !obj.get(path).isJsonNull) {
                return obj.get(path)
            } else {
                obj.add(path, value)
                return value
            }
        }
    }

    var current = this
    for (i in 0 until parts.size - 1) {
        val part = parts[i]
        val next = parts[i + 1]

        current = if (next.toIntOrNull() != null) {
            putIfAbsent(current, part, JsonArray())
        } else {
            putIfAbsent(current, part, JsonObject())
        }
    }
}

enum class StagingState {
    PUBLISHING,
    STAGING,
    PUBLISHED
}

inline fun <reified T : Any> Ref<out Entry>.fieldValue(path: String, value: T) = fieldValue(path, value, T::class.java)

/**
 * Writes [value] into this entry's [path] in staging and tells the panel about it.
 *
 * A failed write is logged and otherwise passes unnoticed. Use [writeFieldValue] where the
 * caller tells a player the write succeeded.
 */
fun Ref<out Entry>.fieldValue(path: String, value: Any, type: Type) {
    writeFieldValue(path, value, type)
}

inline fun <reified T : Any> Ref<out Entry>.writeFieldValue(path: String, value: T): Result<Unit> =
    writeFieldValue(path, value, T::class.java)

/**
 * Writes [value] into this entry's [path] in staging and tells the panel about it, reporting
 * the outcome.
 *
 * An entry moved to another page between resolving the ref and writing resolves its page id
 * against the published library, and the write then lands nowhere. A caller that reports back
 * to a player needs to know that, rather than playing the confirmation sound regardless.
 */
fun Ref<out Entry>.writeFieldValue(path: String, value: Any, type: Type): Result<Unit> {
    val stagingManager = KoinJavaComponent.get<StagingManager>(StagingManager::class.java)
    val gson = KoinJavaComponent.get<Gson>(Gson::class.java, named("dataSerializer"))

    val pageId = pageId
    if (pageId == null) {
        logger.warning("No pageId found for $this. Did you forgot to publish?")
        return Result.failure(IllegalStateException("No page found for $this. Publish the page first."))
    }

    val json = gson.toJsonTree(value, type)
    val result = stagingManager.updateEntryField(pageId, id, path, json)
    if (result.isFailure) {
        logger.warning("Failed to update field: ${result.exceptionOrNull()}")
        return result.map { }
    }

    val clientSynchronizer = KoinJavaComponent.get<ClientSynchronizer>(ClientSynchronizer::class.java)
    clientSynchronizer.sendEntryFieldUpdate(pageId, id, path, json)
    return Result.success(Unit)
}

/**
 * The entry as it exists in staging, including field edits not yet published, or `null` when
 * the entry is not on a staged page or cannot be parsed. Deserialized fresh from the staging
 * json, so it reflects writes made through [fieldValue] that [Ref.get] (the published
 * runtime) does not see until a publish.
 */
fun <E : Entry> Ref<E>.stagedEntry(): E? {
    val published = get() ?: return null
    val pageId = pageId ?: return null
    val stagingManager = KoinJavaComponent.get<StagingManager>(StagingManager::class.java)
    val entries = stagingManager.pages[pageId]?.getAsJsonArray("entries") ?: return null
    val json = entries
        .firstOrNull { it.isJsonObject && it.asJsonObject.get("id")?.asString == id }
        ?.asJsonObject ?: return null
    val gson = KoinJavaComponent.get<Gson>(Gson::class.java, named("dataSerializer"))
    // The target type is the published entry's own class, so the parse already yields an E.
    return runCatching { gson.fromJson(json, published.javaClass) }.getOrNull()
}

fun List<File>.backupAndMove() {
    if (isEmpty()) {
        // Nothing to back up
        return
    }
    val date = Date()
    val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss")

    val backupFolder = plugin.dataFolder["backup/${dateFormat.format(date)}"]
    backupFolder.mkdirs()
    forEach {
        val file = it
        val backupFile = File(backupFolder, file.name)
        file.renameTo(backupFile)
    }
}
