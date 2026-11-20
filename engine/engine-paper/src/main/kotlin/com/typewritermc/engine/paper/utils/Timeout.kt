package com.typewritermc.engine.paper.utils

import com.typewritermc.core.utils.UntickedAsync
import com.typewritermc.core.utils.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import org.koin.core.component.KoinComponent
import kotlin.time.Duration

class Timeout(
    private val duration: Duration,
    private val invoker: () -> Unit,
    private val immediateRunnable: Runnable? = null,
) : KoinComponent {
    private class Pending { var job: Job? = null }
    private val lock = Any()
    private var pending: Pending? = null
    operator fun invoke() {
        immediateRunnable?.run()
        synchronized(lock) {
            if (pending != null) return
            val token = Pending()
            pending = token
            token.job = Dispatchers.UntickedAsync.launch {
                delay(duration)
                val ready = synchronized(lock) {
                    if (pending !== token) false else {
                        pending = null
                        true
                    }
                }
                if (ready) invoker()
            }
        }
    }

    fun force() {
        cancel()
        invoker()
    }

    fun cancel() {
        val previous = synchronized(lock) { pending.also { pending = null } }
        previous?.job?.cancel()
    }
}
