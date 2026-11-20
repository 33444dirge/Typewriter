package com.typewritermc.engine.paper.entry

import com.google.gson.JsonObject

internal class StagedPages {
    private val lock = Any()
    private var values: MutableMap<String, JsonObject>? = null
    @Volatile var publishing: Boolean = false
        private set

    fun load(pages: Map<String, JsonObject>) = synchronized(lock) {
        values = pages.mapValuesTo(linkedMapOf()) { it.value.deepCopy() }
    }

    fun snapshot(): Map<String, JsonObject> = snapshotOrNull() ?: emptyMap()

    fun snapshotOrNull(): Map<String, JsonObject>? = synchronized(lock) {
        values?.mapValues { it.value.deepCopy() }
    }

    fun edit(action: (MutableMap<String, JsonObject>) -> Result<String>): Result<String> = synchronized(lock) {
        if (publishing) return Result.failure(IllegalStateException("Cannot edit pages while publishing"))
        val current = values ?: return Result.failure(IllegalStateException("Pages are not loaded"))
        action(current)
    }

    fun beginPublish(): Map<String, JsonObject>? = synchronized(lock) {
        if (publishing || values == null) return null
        publishing = true
        values!!.mapValues { it.value.deepCopy() }
    }

    fun endPublish() = synchronized(lock) { publishing = false }
}
