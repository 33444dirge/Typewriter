package com.typewritermc.engine.paper.interaction

/** Keeps a pending exit tied to its interaction, including callbacks arriving after input disposal. */
internal class SneakExitControl<T : Any>(
    private val listen: (() -> Unit) -> ConfirmationRegistration,
) {
    private var target: T? = null
    private var pending: T? = null
    private var registration: ConfirmationRegistration? = null
    private var closed = false

    @Synchronized
    fun update(next: T?) {
        if (closed || next === target) return
        val previous = registration
        registration = null
        target = next
        pending = null
        previous?.dispose()
        if (next != null) registration = listen { request(next) }
    }

    @Synchronized
    private fun request(expected: T) {
        if (!closed && target === expected) pending = expected
    }

    @Synchronized
    fun consume(current: T?): Boolean {
        val requested = pending
        pending = null
        return !closed && requested != null && requested === current
    }

    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        target = null
        pending = null
        val previous = registration
        registration = null
        previous?.dispose()
    }
}
