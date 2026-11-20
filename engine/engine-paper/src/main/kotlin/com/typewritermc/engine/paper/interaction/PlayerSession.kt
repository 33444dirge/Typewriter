package com.typewritermc.engine.paper.interaction

import com.typewritermc.engine.paper.utils.entityDispatcher
import com.typewritermc.engine.paper.entry.entity.ViewerSnapshots

import com.typewritermc.core.interaction.*
import com.typewritermc.core.utils.UntickedAsync
import com.typewritermc.core.utils.getAll
import com.typewritermc.core.utils.launch
import com.typewritermc.core.utils.tryCatch
import com.typewritermc.engine.paper.entry.entries.Event
import com.typewritermc.engine.paper.entry.dialogue.DialogueInteraction
import com.typewritermc.engine.paper.events.DialogueInputEvent
import com.typewritermc.engine.paper.events.signalDialogueInput
import com.typewritermc.engine.paper.entry.temporal.TemporalInteraction
import com.typewritermc.engine.paper.utils.config
import com.typewritermc.engine.paper.snippets.snippet
import com.typewritermc.engine.paper.logger
import com.typewritermc.engine.paper.plugin
import com.typewritermc.engine.paper.utils.AVERAGE_SCHEDULING_DELAY_MS
import com.typewritermc.engine.paper.utils.TICK_MS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.bukkit.entity.Player
import org.koin.core.component.KoinComponent
import org.koin.java.KoinJavaComponent
import java.time.Duration
import kotlin.reflect.KClass
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.safeCast
import java.util.concurrent.atomic.AtomicBoolean


class PlayerSession(val player: Player) : KoinComponent {
    private val playerId = player.uniqueId
    private val snapshotLease = ViewerSnapshots.open(playerId)
    private var sessionTickingJob: Job? = null

    internal var scope: InteractionScope? = null
    val interaction: Interaction? get() = scope?.interaction

    private var trackers: List<SessionTracker> = emptyList()
    private var triggerHandlers: List<TriggerHandler> = emptyList()

    private var scheduledEvents = PendingEventBuffer<Event>(4096)
    private var lastOverflowWarning: Long? = null
    private val eventMutex = Mutex()
    private val teardownMutex = Mutex()
    private val closing = AtomicBoolean()
    private val retired = AtomicBoolean()
    private var tornDown = false
    private val sneakExit = SneakExitControl<Interaction> { onPress ->
        PlayerInputConfirmationInput(player).listen(ConfirmationKey.SNEAK, onPress)
    }
    private var sneakExitEnabled = false
    private var sneakExitCinematicPrefixes: List<String> = emptyList()

    private var lastTickTime = System.currentTimeMillis()

    internal fun setup() {
        val enabled by config(
            "dialogue-exit.enabled", true,
            "是否允许下蹲键（默认Shift）退出对白；确认键为SNEAK时自动停用退出，避免同键冲突。修改后重载或重启生效。",
        )
        val cinematicPrefixes by config(
            "dialogue-exit.cinematic-page-prefixes", listOf<Any>("jj_town_"),
            "允许Shift退出的镜头页面ID前缀；空列表表示不接管镜头。对白本身不受此前缀限制。",
        )
        sneakExitEnabled = enabled
        sneakExitCinematicPrefixes = cinematicPrefixes.mapNotNull { (it as? String)?.trim()?.takeIf(String::isNotEmpty) }
        val eventQueueLimit by snippet(
            "session.event-queue-limit", 4096,
            "每玩家待执行事件数量上限（64–65536）；满时拒绝新排队事件并节流告警，防止异常触发积压。修改后重建会话生效。",
        )
        scheduledEvents = PendingEventBuffer(eventQueueLimit.coerceIn(64, 65536))
        triggerHandlers = getKoin().getAll<TriggerHandler>().sortedByDescending { it.priority }
        trackers = getKoin().getAll<SessionTracker>(player)
        trackers.forEach {
            try {
                it.setup()
            } catch (e: Throwable) {
                logger.severe("Failed to setup player session for '${player.name}'.")
                e.printStackTrace()
            }
        }
        ViewerSnapshots.capture(player, snapshotLease)
        sessionTickingJob = startSessionTicking()
    }

    private fun startSessionTicking(): Job = player.entityDispatcher.launch {
        // Wait for the plugin to be enabled
        delay(100)
        while (plugin.isEnabled && isActive) {
            // TODO use kotlin.time.measureTime for these time measurements
            val startTime = System.currentTimeMillis()
            eventMutex.withLock {
                if (closing.get()) return@launch
                runSchedule()
                val now = System.currentTimeMillis()
                val deltaTime = now - lastTickTime
                lastTickTime = now
                tick(Duration.ofMillis(deltaTime))
            }
            val endTime = System.currentTimeMillis()
            // Wait for the remainder or the tick
            val wait = TICK_MS - (endTime - startTime) - AVERAGE_SCHEDULING_DELAY_MS
            if (wait > 0) delay(wait)
            else {
                if (wait < -100) logger.warning("The session ticker for ${player.name} is running behind! Took ${endTime - startTime}ms (if this only happens occasionally, it's fine)")
                delay(1)
            }
        }
    }

    /**
     * Adds an event to the schedule. If there is already an event scheduled, it will be merged with
     */
    suspend fun addToSchedule(event: Event) = eventMutex.withLock {
        if (closing.get()) return@withLock
        if (!scheduledEvents.offer(event)) {
            val now = System.nanoTime()
            val previous = lastOverflowWarning
            if (previous == null || now - previous >= 30_000_000_000L) {
                lastOverflowWarning = now
                logger.warning("Event queue limit exceeded for $playerId; new queued event rejected")
            }
        }
    }

    /**
     * Forces an event to be executed.
     * This will bypass the schedule and execute the event immediately.
     * This will also clear the schedule.
     */
    suspend fun forceEvent(event: Event) {
        eventMutex.withLock {
            if (closing.get()) return@withLock
            runSchedule(event)
        }
    }

    suspend fun tick(deltaTime: Duration) {
        if (closing.get()) return
        scope?.tick(deltaTime)
        trackers.forEach {
            tryCatch { it.tick() }
        }
        ViewerSnapshots.capture(player, snapshotLease)
    }

    private suspend fun runSchedule(forcedEvent: Event? = null) {
        if (sneakExit.consume(interaction)) {
            val exitingEntry = when (val active = interaction) {
                is DialogueInteraction -> active.currentEntry.id
                is TemporalInteraction -> active.pageId
                else -> null
            }
            exitingEntry?.let {
                player.signalDialogueInput(it, DialogueInputEvent.Action.EXIT)
            }
            val previousScope = scope
            scope = null
            sneakExit.update(null)
            scheduledEvents.clear()
            previousScope?.teardown()
            return
        }
        if (scheduledEvents.isEmpty && forcedEvent == null) return
        val events = scheduledEvents.drain()
        if (forcedEvent != null) events.add(forcedEvent)
        if (events.isEmpty()) return
        /// We want to dedupe events as much as possible to prevent auto clickers, etc., from triggering the same event many times.
        /// Possibly allowing duplication glitches.
        val mergedEvents = events
            .filter { it.triggers.isNotEmpty() }
            .groupBy { it.context }
            .mapValues {
                it.value.reduce { acc, event -> acc.merge(event) }.distinct()
            }
            .values
        onEvent(mergedEvents)
        refreshSneakExit()
    }

    private fun refreshSneakExit() {
        val current = interaction
        val target = when {
            closing.get() || !sneakExitEnabled || confirmationKey == ConfirmationKey.SNEAK -> null
            current is DialogueInteraction -> current
            current is TemporalInteraction && sneakExitCinematicPrefixes.any(current.pageId::startsWith) -> current
            else -> null
        }
        sneakExit.update(target)
    }

    /** Handles an event. */
    private suspend fun onEvent(events: Collection<Event>) {
        var interactionLifecycle = InteractionLifecycle.NONE
        var boundLifecycle = InteractionLifecycle.NONE
        val nextInteractions = mutableListOf<Interaction>()
        val nextBounds = mutableListOf<InteractionBound>()
        val todo = ArrayDeque(events.map(Event::filterAllowedTriggers))

        // To prevent infinite loops, we limit the number of recursive calls.
        var loops = 0
        while (todo.isNotEmpty() && loops < 10000) {
            loops++
            val triggering = todo.removeFirst()

            if (triggering.triggers.isEmpty()) continue

            val addingEvents = mutableListOf<Event>()

            triggerHandlers.forEach { handler ->
                fun TriggerContinuation.apply() {
                    when (this) {
                        TriggerContinuation.Nothing -> {}
                        is TriggerContinuation.Append -> addingEvents.addAll(this.events)
                        is TriggerContinuation.StartInteraction -> nextInteractions.add(this.interaction)
                        is TriggerContinuation.KeepInteraction -> interactionLifecycle = InteractionLifecycle.KEEP
                        TriggerContinuation.EndInteraction -> {
                            if (interactionLifecycle == InteractionLifecycle.KEEP) return
                            interactionLifecycle = InteractionLifecycle.END
                        }

                        is TriggerContinuation.StartInteractionBound -> nextBounds.add(this.bound)
                        is TriggerContinuation.EndInteractionBound -> boundLifecycle = InteractionLifecycle.END
                        is TriggerContinuation.Multi -> this.continuations.forEach { it.apply() }
                    }
                }

                try {
                    handler.trigger(triggering, interaction).apply()
                } catch (t: Throwable) {
                    logger.severe("Exception thrown while triggering handler $handler.")
                    t.printStackTrace()
                }
            }

            // We want to do the filtering before the next event
            //  but after all the handlers to make sure the criteria match as expected
            todo.addAll(addingEvents.map(Event::filterAllowedTriggers))
        }

        if (nextInteractions.isEmpty() && interactionLifecycle == InteractionLifecycle.END) {
            scope?.teardown()
            scope = null
            return
        }

        val nextBound = nextBounds.maxByOrNull { it.priority } ?: InteractionBound.Empty
        val scope = scope
        if (scope != null && (scope.bound.priority <= nextBound.priority || boundLifecycle == InteractionLifecycle.END)) {
            scope.swapBound(nextBound)
        }

        if (nextInteractions.isEmpty() && interactionLifecycle == InteractionLifecycle.KEEP) {
            return
        }

        val nextInteraction = nextInteractions.maxByOrNull { it.priority } ?: return
        // Only more or equal important interactions can override.
        if (nextInteraction.priority < (interaction?.priority
                ?: Int.MIN_VALUE) && interactionLifecycle != InteractionLifecycle.END
        ) {
            return
        }

        if (scope == null) {
            this.scope = InteractionScope(nextInteraction, nextBound)
            try {
                this.scope?.initialize()
            } catch (exception: Exception) {
                logger.severe("Something when wrong while trying to start ${nextInteraction}, skipping...")
                exception.printStackTrace()
                this.scope?.teardown()
                this.scope = null
            }
        } else {
            try {
                this.scope?.swapInteraction(nextInteraction)
            } catch (exception: Exception) {
                logger.severe("Exception thrown while trying to start ${nextInteraction}, skipping....")
                exception.printStackTrace()
                this.scope?.teardown()
                this.scope = null
            }
        }
    }

    fun <T : SessionTracker> tracker(klass: KClass<T>): T? {
        return klass.safeCast(trackers.firstOrNull { it::class.isSubclassOf(klass) })
    }

    fun retire() {
        closing.set(true)
        sneakExit.close()
        ViewerSnapshots.remove(player.uniqueId, snapshotLease)
        sessionTickingJob?.cancel()
        if (retired.compareAndSet(false, true)) {
            listOfNotNull(scope?.bound, scope?.interaction).filterIsInstance<org.bukkit.event.Listener>().forEach {
                try {
                    org.bukkit.event.HandlerList.unregisterAll(it)
                } catch (error: Throwable) {
                    logger.log(java.util.logging.Level.SEVERE, "Failed to retire player interaction listener", error)
                }
            }
            try {
                (scope?.interaction as? RetirableInteraction)?.retire()
            } catch (error: Throwable) {
                logger.log(java.util.logging.Level.SEVERE, "Failed to retire player interaction resources", error)
            }
        }
    }

    suspend fun releaseRetiredResources() {
        retire()
        teardownMutex.withLock {
            if (tornDown) return@withLock
            sessionTickingJob?.cancelAndJoin()
            sessionTickingJob = null
            eventMutex.withLock {
                trackers.filterIsInstance<RetirableSessionTracker>().forEach {
                    try {
                        it.retire()
                    } catch (error: Throwable) {
                        logger.log(java.util.logging.Level.SEVERE, "Failed to retire session tracker", error)
                    }
                }
                scope = null
                trackers = emptyList()
                triggerHandlers = emptyList()
                scheduledEvents.clear()
                tornDown = true
            }
        }
    }

    suspend fun teardown() {
        closing.set(true)
        sneakExit.close()
        ViewerSnapshots.remove(player.uniqueId, snapshotLease)
        teardownMutex.withLock {
            if (tornDown) return@withLock
            val ticker = sessionTickingJob
            check(ticker !== currentCoroutineContext()[Job]) { "A player session cannot tear down its own ticker" }
            ticker?.cancelAndJoin()
            sessionTickingJob = null
            eventMutex.withLock {
                val previousScope = scope
                val previousTrackers = trackers
                scope = null
                trackers = emptyList()
                triggerHandlers = emptyList()
                scheduledEvents.clear()
                try {
                    previousScope?.teardown()
                } catch (error: Throwable) {
                    logger.log(java.util.logging.Level.SEVERE, "Failed to tear down player interaction", error)
                }
                previousTrackers.forEach { tracker ->
                    try {
                        tracker.teardown()
                    } catch (error: Throwable) {
                        logger.log(java.util.logging.Level.SEVERE, "Failed to tear down session tracker", error)
                    }
                }
                tornDown = true
            }
        }
    }
}

internal val Player.interactionScope: InteractionScope?
    get() = with(KoinJavaComponent.get<PlayerSessionManager>(PlayerSessionManager::class.java)) {
        session?.scope
    }


val Player.interactionContext: InteractionContext?
    get() = with(KoinJavaComponent.get<PlayerSessionManager>(PlayerSessionManager::class.java)) {
        session?.interaction?.context
    }


private enum class InteractionLifecycle {
    NONE, KEEP, END
}
