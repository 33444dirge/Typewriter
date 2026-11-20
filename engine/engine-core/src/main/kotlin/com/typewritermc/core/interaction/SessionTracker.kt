package com.typewritermc.core.interaction

/** Clears tracker resources without player access after owner scheduling has stopped. */
interface RetirableSessionTracker {
    fun retire()
}

interface SessionTracker {
    fun setup()
    fun tick()
    fun teardown()
}
