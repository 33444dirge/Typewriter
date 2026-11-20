package com.typewritermc.engine.paper.interaction

import com.typewritermc.core.interaction.InteractionContext
import com.typewritermc.core.utils.launch
import com.typewritermc.engine.paper.entry.dialogue.isInDialogue
import com.typewritermc.engine.paper.entry.entries.Event
import com.typewritermc.engine.paper.entry.entries.EventTrigger
import com.typewritermc.engine.paper.logger
import com.typewritermc.engine.paper.plugin
import com.typewritermc.engine.paper.utils.server
import com.typewritermc.engine.paper.utils.hasStateRecovery
import com.typewritermc.engine.paper.utils.recoverInterruptedState
import com.typewritermc.engine.paper.utils.foliaScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch as launchCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope
import com.typewritermc.engine.paper.utils.entityDispatcher
import com.typewritermc.engine.paper.entry.entity.ViewerSnapshots
import lirand.api.extensions.server.registerEvents
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.koin.core.component.KoinComponent
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class PlayerSessionManager : Listener, KoinComponent {
    private val sessions = ConcurrentHashMap<UUID, PlayerSession>()
    private val loaded = AtomicBoolean()
    private val lifecycle = Any()

    val Player.session: PlayerSession?
        get() = sessions[uniqueId]


    /** Some triggers start dialogue. Though we don't want to trigger the starting of dialogue multiple times,
     * we need to check if the player is already in a dialogue.
     *
     * @param player The player who interacted
     * @param initialTriggers The trigger that should be fired after the quest started
     * @param continueTrigger The trigger that should be fired if the quest is already active
     */
    fun startDialogueWithOrTriggerEvent(
        player: Player,
        context: InteractionContext,
        initialTriggers: List<EventTrigger>,
        continueTrigger: EventTrigger? = null
    ) {
        if (player.isInDialogue && continueTrigger != null) {
            triggerEvent(Event(player, context, continueTrigger))
        } else {
            triggerEvent(Event(player, context, initialTriggers))
        }
    }

    /**
     * Triggers a list of actions.
     *
     * @param player The player who interacted
     * @param triggers A list of triggers that should be fired.
     */
    fun triggerActions(player: Player, context: InteractionContext, triggers: List<EventTrigger>) {
        triggerEvent(Event(player, context, triggers))
    }

    /**
     * Forces an event to be executed.
     * This will bypass the event queue and execute the event immediately.
     * This is useful for events that need to be executed immediately.
     * **This should only be used sparingly.**
     *
     * @param player The player who interacted
     * @param triggers The trigger that should be fired.
     */
    suspend fun forceTriggerActions(player: Player, context: InteractionContext, triggers: List<EventTrigger>) {
        withContext(player.entityDispatcher) { player.session?.forceEvent(Event(player, context, triggers)) }
    }


    private fun triggerEvent(event: Event) {
        // If the event is empty, we don't need to do anything
        if (event.triggers.isEmpty()) return

        event.player.entityDispatcher.launch {
            try {
                event.player.session?.addToSchedule(event)
            } catch (e: Exception) {
                logger.severe("An error occurred while handling event ${event}: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    fun initialize() {
        plugin.registerEvents(this)
    }

    // When a player joins the server, we need to create a session for them.
    @EventHandler(priority = EventPriority.LOWEST)
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player
        if (!player.hasStateRecovery()) createSession(player)
        else player.entityDispatcher.launch {
            if (player.recoverInterruptedState()) createSession(player)
        }
    }

    // When a player leaves the server, we need to end the session.
    @EventHandler(priority = EventPriority.HIGHEST)
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val session = sessions.remove(event.player.uniqueId) ?: return
        session.retire()
        CoroutineScope(event.player.entityDispatcher).launchCoroutine(start = CoroutineStart.UNDISPATCHED) {
            session.teardown()
        }
    }

    suspend fun load() {
        ViewerSnapshots.initialize()
        loaded.set(true)
        supervisorScope {
            server.onlinePlayers.toList().forEach { player ->
                launchCoroutine {
                    try {
                        withContext(player.entityDispatcher) {
                            if (player.recoverInterruptedState()) createSession(player)
                        }
                    } catch (error: Exception) {
                        currentCoroutineContext().ensureActive()
                        logger.log(java.util.logging.Level.SEVERE, "Failed to initialize a player session", error)
                    }
                }
            }
        }
    }

    private fun createSession(player: Player) {
        var created = false
        val session = synchronized(lifecycle) {
            if (!loaded.get()) return
            sessions[player.uniqueId] ?: PlayerSession(player).also {
                sessions[player.uniqueId] = it
                created = true
            }
        }
        if (created) session.setup()
    }

    suspend fun unload() = stopSessions()

    suspend fun shutdown() = stopSessions()

    private suspend fun stopSessions() {
        val previous = synchronized(lifecycle) {
            loaded.set(false)
            sessions.toList().also { entries ->
                entries.forEach { (id, session) -> sessions.remove(id, session) }
            }
        }
        val failures = mutableListOf<Throwable>()
        try {
            previous.forEach { (_, session) ->
                try {
                    if (foliaScheduler.active()) {
                        withContext(session.player.entityDispatcher) { session.teardown() }
                    } else session.releaseRetiredResources()
                } catch (error: Throwable) {
                    failures += error
                }
            }
        } finally {
            ViewerSnapshots.clear()
        }
        if (failures.isNotEmpty()) {
            throw IllegalStateException("Failed to close ${failures.size} Typewriter player sessions").apply {
                failures.forEach(::addSuppressed)
            }
        }
    }
}
