package com.typewritermc.basic.entries.cinematic

import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicReference

internal class CameraCoordinateHandoff {
    private sealed interface Phase {
        data object Playing : Phase
        data class Restoring(val teleportId: Int? = null) : Phase
        data object Confirmed : Phase
        data object Closed : Phase
    }

    private val phase = AtomicReference<Phase>(Phase.Playing)
    private val confirmation = CompletableDeferred<Boolean>()

    fun beginRestore() {
        phase.compareAndSet(Phase.Playing, Phase.Restoring())
    }

    fun outgoingY(y: Double, teleportId: Int): Double {
        while (true) {
            when (val current = phase.get()) {
                Phase.Playing -> return y + OFFSET
                is Phase.Restoring -> {
                    if (phase.compareAndSet(current, Phase.Restoring(teleportId))) return y
                }
                Phase.Confirmed, Phase.Closed -> return y
            }
        }
    }

    fun incomingY(y: Double): Double? = when (phase.get()) {
        Phase.Playing -> y - OFFSET
        is Phase.Restoring -> null
        Phase.Confirmed, Phase.Closed -> y
    }

    fun confirm(teleportId: Int) {
        val current = phase.get() as? Phase.Restoring ?: return
        if (current.teleportId != teleportId) return
        if (phase.compareAndSet(current, Phase.Confirmed)) confirmation.complete(true)
    }

    suspend fun awaitConfirmation(): Boolean = confirmation.await()

    fun close() {
        phase.set(Phase.Closed)
        confirmation.complete(false)
    }

    private companion object {
        const val OFFSET = 500.0
    }
}
