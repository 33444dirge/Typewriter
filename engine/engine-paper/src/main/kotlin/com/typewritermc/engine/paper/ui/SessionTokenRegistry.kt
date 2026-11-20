package com.typewritermc.engine.paper.ui

import java.time.Duration
import java.util.UUID

/** Bounded, idle-expiring links. Only immutable session metadata is retained. */
internal class SessionTokenRegistry(
    private val capacity: Int = 1024,
    idleTimeout: Duration = Duration.ofMinutes(30),
    private val clock: () -> Long = System::nanoTime,
) {
    private data class Record(var session: SessionData, var touched: Long)

    private val idleNanos = idleTimeout.toNanos()
    private val tokens = LinkedHashMap<UUID, Record>(16, 0.75f, true)
    private val players = HashMap<UUID?, UUID>()
    private var closed = false

    init {
        require(capacity > 0 && idleNanos > 0)
    }

    @Synchronized
    fun generate(playerId: UUID?): UUID {
        check(!closed) { "Typewriter web communication is stopped" }
        val now = clock()
        expire(now)
        players[playerId]?.let { token ->
            tokens[token]?.let { it.touched = now; return token }
        }
        while (tokens.size >= capacity) removeOldest()
        return UUID.randomUUID().also { token ->
            tokens[token] = Record(SessionData.create(playerId), now)
            players[playerId] = token
        }
    }

    @Synchronized
    fun get(token: UUID): SessionData? {
        if (closed) return null
        val now = clock()
        expire(now)
        return tokens[token]?.also { it.touched = now }?.session
    }

    @Synchronized
    fun connected(token: UUID, connectionId: UUID) {
        get(token) ?: return
        tokens[token]?.let { it.session = it.session.copy(lastConnectionId = connectionId) }
    }

    @Synchronized
    fun close() {
        closed = true
        tokens.clear()
        players.clear()
    }

    private fun expire(now: Long) {
        while (tokens.isNotEmpty() && now - tokens.entries.first().value.touched >= idleNanos) removeOldest()
    }

    private fun removeOldest() {
        val iterator = tokens.entries.iterator()
        val oldest = iterator.next()
        players.remove(oldest.value.session.playerId, oldest.key)
        iterator.remove()
    }
}
