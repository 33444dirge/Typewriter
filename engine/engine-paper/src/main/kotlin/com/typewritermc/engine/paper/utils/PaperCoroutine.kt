package com.typewritermc.engine.paper.utils

import com.typewritermc.engine.paper.plugin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Entity
import kotlin.coroutines.CoroutineContext

val foliaScheduler: FoliaScheduler by lazy { FoliaScheduler(plugin) }

private fun rejectContinuation(context: CoroutineContext, block: Runnable, reason: String) {
    context[Job]?.cancel(CancellationException(reason))
    Dispatchers.IO.dispatch(context, block)
}

private object GlobalDispatcher : CoroutineDispatcher() {
    override fun isDispatchNeeded(context: CoroutineContext): Boolean =
        !foliaScheduler.active() || !Bukkit.isGlobalTickThread()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        foliaScheduler.global(block, {
            rejectContinuation(context, block, "Typewriter global scheduling stopped")
        }, 1L)
    }
}

private object AsyncDispatcher : CoroutineDispatcher() {
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        foliaScheduler.async(block) {
            rejectContinuation(context, block, "Typewriter async scheduling stopped")
        }
    }
}

private class OwnerDispatcher(private val entity: Entity) : CoroutineDispatcher() {
    override fun isDispatchNeeded(context: CoroutineContext): Boolean =
        !foliaScheduler.active() || !Bukkit.isOwnedByCurrentRegion(entity)

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        foliaScheduler.atEntity(entity, block, {
            rejectContinuation(context, block, "Typewriter entity retired")
        }, 1L)
    }
}

private class RegionDispatcher(location: Location) : CoroutineDispatcher() {
    private val target = location.clone()

    override fun isDispatchNeeded(context: CoroutineContext): Boolean =
        !foliaScheduler.active() || !Bukkit.isOwnedByCurrentRegion(target)

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        foliaScheduler.atRegion(target, block, {
            rejectContinuation(context, block, "Typewriter region scheduling stopped")
        }, 1L)
    }
}

/** For global server state and console commands, never player or block state. */
val Dispatchers.Sync: CoroutineDispatcher get() = GlobalDispatcher

/** For I/O and snapshots, without access to live Bukkit state. */
val Dispatchers.TickedAsync: CoroutineDispatcher get() = AsyncDispatcher

/** Follows the entity across region transfers and cancels work after retirement. */
val Entity.entityDispatcher: CoroutineDispatcher get() = OwnerDispatcher(this)

/** Owns a copied block or chunk location, independent of the initiating player. */
val Location.regionDispatcher: CoroutineDispatcher get() = RegionDispatcher(this)
