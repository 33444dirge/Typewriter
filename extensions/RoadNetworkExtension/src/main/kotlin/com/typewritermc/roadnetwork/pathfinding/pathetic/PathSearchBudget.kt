package com.typewritermc.roadnetwork.pathfinding.pathetic

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger

/** Bounds both searches holding snapshots and coroutines waiting to create them. */
internal class PathSearchBudget(concurrent: Int, waiting: Int) {
    private val permits = Semaphore(concurrent)
    private val limit = concurrent + waiting
    private val admitted = AtomicInteger()

    init { require(concurrent > 0 && waiting >= 0 && limit > 0) }

    suspend fun <T> run(block: suspend () -> T): T? {
        while (true) {
            val count = admitted.get()
            if (count >= limit) return null
            if (admitted.compareAndSet(count, count + 1)) break
        }
        try {
            return permits.withPermit { block() }
        } finally {
            admitted.decrementAndGet()
        }
    }
}
