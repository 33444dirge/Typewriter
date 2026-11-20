package com.typewritermc.engine.paper.interaction

/** Accessed only while the session's event mutex is held. */
internal class PendingEventBuffer<E>(private val capacity: Int) {
    private var pending = ArrayList<E>()

    init { require(capacity > 0) }

    val isEmpty: Boolean get() = pending.isEmpty()

    fun offer(event: E): Boolean {
        if (pending.size >= capacity) return false
        pending.add(event)
        return true
    }

    fun drain(): ArrayList<E> = pending.also { pending = ArrayList() }

    fun clear() { pending = ArrayList() }
}
