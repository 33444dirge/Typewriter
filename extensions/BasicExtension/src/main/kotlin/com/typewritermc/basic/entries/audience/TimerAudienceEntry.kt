package com.typewritermc.basic.entries.audience

import com.typewritermc.core.books.pages.Colors
import com.typewritermc.core.entries.Ref
import com.typewritermc.core.entries.emptyRef
import com.typewritermc.core.extension.annotations.Entry
import com.typewritermc.core.interaction.context
import com.typewritermc.core.utils.launch
import com.typewritermc.engine.paper.entry.TriggerableEntry
import com.typewritermc.engine.paper.entry.entries.AudienceDisplay
import com.typewritermc.engine.paper.entry.entries.AudienceEntry
import com.typewritermc.engine.paper.entry.entries.ConstVar
import com.typewritermc.engine.paper.entry.entries.Var
import com.typewritermc.engine.paper.entry.triggerFor
import com.typewritermc.engine.paper.logger
import com.typewritermc.engine.paper.utils.entityDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import org.bukkit.entity.Player
import java.time.Duration
import java.util.*
import java.util.concurrent.ConcurrentHashMap

@Entry(
    "timer_audience",
    "Triggers an action every specified duration when the player is in the audience",
    Colors.GREEN,
    "mdi:timer-outline"
)
/**
 * The `Timer Audience` entry is an audience filter that triggers an action every specified duration when the player is in the audience.
 *
 * :::caution
 * Very short durations can cause performance issues, and may be inaccurate.
 * :::
 *
 * ## How could this be used?
 * This can be used to trigger a sequence every few seconds.
 */
class TimerAudienceEntry(
    override val id: String = "",
    override val name: String = "",
    val duration: Var<Duration> = ConstVar(Duration.ofSeconds(1)),
    val onTimer: Ref<TriggerableEntry> = emptyRef(),
) : AudienceEntry {
    override suspend fun display(): AudienceDisplay = TimerAudienceDisplay(duration, onTimer)
}

class TimerAudienceDisplay(
    private val duration: Var<Duration>,
    private val onTimer: Ref<TriggerableEntry>,
) : AudienceDisplay() {
    private val jobs = ConcurrentHashMap<UUID, Job>()

    override fun onPlayerAdd(player: Player) {
        val duration = duration.get(player)
        if (duration.isZero || duration.isNegative) {
            logger.warning("Timer duration must be positive, otherwise it will infinitely trigger.")
            return
        }
        val playerId = player.uniqueId
        val job = player.entityDispatcher.launch {
            while (!isClosed && player in this@TimerAudienceDisplay) {
                delay(duration.toMillis().coerceAtLeast(1L))
                if (!isClosed && player in this@TimerAudienceDisplay) onTimer.triggerFor(player, context())
            }
        }
        jobs.put(playerId, job)?.cancel()
        job.invokeOnCompletion { jobs.remove(playerId, job) }
    }

    override fun onPlayerRemove(player: Player) {
        jobs.remove(player.uniqueId)?.cancel()
    }

    override fun dispose() {
        jobs.values.forEach(Job::cancel)
        jobs.clear()
        super.dispose()
    }
}
