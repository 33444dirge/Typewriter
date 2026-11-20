package com.typewritermc.basic.entries.cinematic

/** Serializes acquisition/ticks with retirement, which may arrive after the entity owner is gone. */
internal class StationaryCameraResources(private val onError: (Exception) -> Unit) {
    private val cleanups = ArrayDeque<() -> Unit>()
    @Volatile
    var closed = false
        private set

    @Synchronized
    fun use(block: StationaryCameraResources.() -> Unit) {
        if (!closed) block()
    }

    @Synchronized
    fun remember(cleanup: () -> Unit) {
        check(!closed) { "Camera already retired" }
        cleanups.addFirst(cleanup)
    }

    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        while (cleanups.isNotEmpty()) {
            try {
                cleanups.removeFirst().invoke()
            } catch (error: Exception) {
                runCatching { onError(error) }
            }
        }
    }
}
