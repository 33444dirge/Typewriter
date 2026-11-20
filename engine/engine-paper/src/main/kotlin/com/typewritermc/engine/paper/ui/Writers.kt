package com.typewritermc.engine.paper.ui

import com.corundumstudio.socketio.SocketIOServer
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.atomic.AtomicReference

data class Writer(
    val id: String = "",
    val iconUrl: String? = null,
    val pageId: String? = null,
    val entryId: String? = null,
    val field: String? = null,
) {
    companion object

    fun merge(writer: Writer): Writer {
        return Writer(
            id = writer.id,
            iconUrl = writer.iconUrl ?: iconUrl,
            pageId = writer.pageId,
            entryId = writer.entryId,
            field = writer.field,
        )
    }
}

class Writers : KoinComponent {
    private val communicationHandler: CommunicationHandler by inject()
    private data class Roster(val writers: List<Writer> = emptyList(), val lastSynced: Long = 0, val closed: Boolean = false)
    private val roster = AtomicReference(Roster())


    private fun addWriter(writer: Writer) {
        update { writers -> writers.filterNot { it.id == writer.id } + writer }
    }

    fun addWriter(id: String, iconUrl: String?) {
        addWriter(Writer(id, iconUrl))
    }

    private fun updateWriter(writer: Writer) {
        update { writers -> writers.map { if (it.id == writer.id) it.merge(writer) else it } }
        syncWriters()
    }

    fun removeWriter(id: String) {
        update { writers -> writers.filter { it.id != id } }
        syncWriters()
    }

    fun updateWriter(id: String, data: String) {
        val map: Map<String, Any> = Gson().fromJson(data, object : TypeToken<Map<String, Any>>() {}.type)

        val writer = Writer(
            id = id,
            pageId = map["pageId"] as String?,
            entryId = map["entryId"] as String?,
            field = map["field"] as String?,
        )

        updateWriter(writer)
    }

    /**
     * This is purly for backup. It will sync the writers every 30 seconds.
     * This is to make sure that the writers are synced even if the client
     * disconnects.
     */
    private fun syncWriters() {
        val previous = roster.get()
        if (previous.closed) return
        val now = System.currentTimeMillis()
        if (now - previous.lastSynced < 30_000) return

        val clientIds =
            communicationHandler.server?.allClients?.map { it.sessionId.toString() }
                ?: return
        val retained = previous.writers.filter { it.id in clientIds }
        val missing = clientIds.filter { id -> retained.none { it.id == id } }.map { id ->
            Writer(id, communicationHandler.getIconUrl(id))
        }
        roster.compareAndSet(previous, previous.copy(writers = retained + missing, lastSynced = now))
    }

    private fun update(change: (List<Writer>) -> List<Writer>) {
        roster.updateAndGet { if (it.closed) it else it.copy(writers = change(it.writers)) }
    }

    fun dispose() {
        roster.updateAndGet { it.copy(writers = emptyList(), closed = true) }
    }

    fun serialize(): String {
        return Gson().toJson(roster.get().writers)
    }
}


fun SocketIOServer?.broadcastWriters(writers: Writers) {
    this?.broadcastOperations?.sendEvent("updateWriters", writers.serialize())
}
