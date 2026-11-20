package com.typewritermc.engine.paper.entry.entity

import com.typewritermc.engine.paper.entry.entries.EntityProperty
import com.typewritermc.engine.paper.logger
import org.bukkit.entity.Player
import com.typewritermc.engine.paper.utils.foliaScheduler
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class ActivityManager<Context : ActivityContext>(
    private val activity: EntityActivity<in Context>,
    private val reportViolation: (String) -> Unit = { logger.severe(it) },
) {
    private enum class State { CREATED, INITIALIZING, ACTIVE, DISPOSED }

    private var state = State.CREATED
    private var reportedTickViolation = false
    private var resourcesDisposed = false
    private data class ViewerChange(val context: SharedActivityContext, val viewer: Player, val added: Boolean)
    private val pendingViewers = mutableListOf<ViewerChange>()
    private val queuedTick = AtomicBoolean()
    private val queuedContext = AtomicReference<Context?>()
    private data class Snapshot(val position: PositionProperty, val properties: List<EntityProperty>)
    @Volatile private var snapshot = Snapshot(activity.currentPosition, activity.currentProperties.toList())

    val position: PositionProperty
        get() = snapshot.position

    val activeProperties: List<EntityProperty>
        get() = snapshot.properties

    @Synchronized
    fun initialize(context: Context) {
        check(state == State.CREATED) { "An activity manager can only be initialized once, this one is $state" }
        state = State.INITIALIZING
        inContext(context, ::abandon) {
            synchronized(this) {
                if (state != State.INITIALIZING) return@synchronized
                try {
                    activity.activate(context, position)
                    publish()
                    state = State.ACTIVE
                    val viewers = pendingViewers.toList()
                    pendingViewers.clear()
                    viewers.forEach { change ->
                        if (change.added) addedViewer(change.context, change.viewer)
                        else removedViewer(change.context, change.viewer)
                    }
                } catch (error: Throwable) {
                    state = State.DISPOSED
                    pendingViewers.clear()
                    try {
                        activity.deactivate(context)
                    } catch (cleanup: Throwable) {
                        error.addSuppressed(cleanup)
                    }
                    try {
                        disposeResources()
                    } catch (cleanup: Throwable) {
                        error.addSuppressed(cleanup)
                    }
                    throw error
                }
            }
        }
    }

    @Synchronized
    fun tick(context: Context) {
        if (state == State.INITIALIZING) return
        if (state != State.ACTIVE) {
            if (reportedTickViolation) return
            reportedTickViolation = true
            reportViolation("Ticked an activity manager that is $state. This is a Typewriter bug, please report it.")
            return
        }
        val viewer = context.viewers.firstOrNull { foliaScheduler.owns(it) } ?: context.viewers.firstOrNull()
        if (viewer != null && !foliaScheduler.owns(viewer)) {
            queuedContext.set(context)
            if (!queuedTick.compareAndSet(false, true)) return
            foliaScheduler.atEntity(viewer, {
                val latest = queuedContext.getAndSet(null)
                queuedTick.set(false)
                latest?.let { tick(it) }
            }, {
                queuedContext.set(null)
                queuedTick.set(false)
            }, 1L)
        } else {
            activity.tick(context)
            publish()
        }
    }

    @Synchronized
    fun addedViewer(context: SharedActivityContext, viewer: Player) {
        if (state == State.INITIALIZING) {
            pendingViewers += ViewerChange(context, viewer, true)
            return
        }
        if (state != State.ACTIVE) return
        if (!foliaScheduler.owns(viewer)) {
            foliaScheduler.atEntity(viewer, { addedViewer(context, viewer) }, null, 1L)
            return
        }
        when (activity) {
            is SharedEntityActivity -> activity.addedViewer(context, viewer)
        }
        publish()
    }

    @Synchronized
    fun removedViewer(context: SharedActivityContext, viewer: Player) {
        if (state == State.INITIALIZING) {
            pendingViewers += ViewerChange(context, viewer, false)
            return
        }
        if (state != State.ACTIVE) return
        if (!foliaScheduler.owns(viewer)) {
            foliaScheduler.atEntity(viewer, { removedViewer(context, viewer) }, null, 1L)
            return
        }
        when (activity) {
            is SharedEntityActivity -> activity.removedViewer(context, viewer)
        }
        publish()
    }

    @Synchronized
    fun dispose(context: Context) {
        if (state == State.DISPOSED) return
        val deactivate = state == State.ACTIVE
        state = State.DISPOSED
        queuedContext.set(null)
        pendingViewers.clear()
        inContext(context, ::abandon) {
            synchronized(this) {
                if (resourcesDisposed) return@synchronized
                try {
                    if (deactivate) activity.deactivate(context)
                } finally {
                    disposeResources()
                }
            }
        }
    }

    private fun publish() {
        snapshot = Snapshot(activity.currentPosition, activity.currentProperties.toList())
    }

    @Synchronized
    private fun abandon() {
        state = State.DISPOSED
        queuedContext.set(null)
        pendingViewers.clear()
        disposeResources()
    }

    private fun disposeResources() {
        if (resourcesDisposed) return
        resourcesDisposed = true
        activity.dispose()
    }

    private fun inContext(context: ActivityContext, rejected: () -> Unit, action: () -> Unit) {
        val viewer = context.viewers.firstOrNull { foliaScheduler.owns(it) } ?: context.viewers.firstOrNull()
        if (viewer == null || foliaScheduler.owns(viewer)) action()
        else foliaScheduler.atEntity(viewer, action, rejected, 1L)
    }
}
