package com.typewritermc.engine.paper.entry.entries

import com.google.common.collect.Sets
import com.typewritermc.core.entries.Ref
import com.typewritermc.core.entries.ref
import com.typewritermc.core.extension.annotations.Help
import com.typewritermc.core.extension.annotations.Tags
import com.typewritermc.engine.paper.entry.*
import com.typewritermc.engine.paper.plugin
import com.typewritermc.engine.paper.utils.server
import lirand.api.extensions.events.unregister
import org.bukkit.entity.Player
import org.bukkit.Bukkit
import com.typewritermc.engine.paper.utils.foliaScheduler
import org.bukkit.event.Listener
import org.koin.java.KoinJavaComponent.get
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException


/**
 * When an `AudienceEntry` is marked with `@ChildOnly`, it will only be used as a child of an `AudienceFilterEntry`.
 * If the entry is at the root of the manifest, it will not be instantiated.
 */
@Target(AnnotationTarget.CLASS)
annotation class ChildOnly

@Tags("audience")
interface AudienceEntry : ManifestEntry, PlaceholderEntry {
    suspend fun display(): AudienceDisplay

    override fun parser() = placeholderParser {
        literal("players") {
            supply {
                val manager = get<AudienceManager>(AudienceManager::class.java)
                val display = manager[ref()]
                display?.players?.joinToString(", ") { it.name } ?: ""
            }
        }
    }
}


@Tags("audience_filter")
interface AudienceFilterEntry : AudienceEntry {
    val children: List<Ref<out AudienceEntry>>
    override suspend fun display(): AudienceFilter
}

interface Invertible {
    @Help("The audience will be the players that do not match the criteria.")
    val inverted: Boolean
}

interface TickableDisplay {
    fun tick()
}

enum class AudienceDisplayState(val displayName: String, val color: String) {
    // When the player is in the audience
    IN_AUDIENCE("In Audience", "green"),

    // When the player is not passing the audience filter
    BLOCKED("Blocked", "red"),

    // When all the parents block the player
    NOT_CONSIDERED("Not Considered", "gray"),
    ;
}

abstract class AudienceDisplay : Listener {
    @Volatile
    var isActive = false
        private set
    private val memberIds: MutableSet<UUID> = Sets.newConcurrentHashSet()
    private val pendingTicks = ConcurrentHashMap<UUID, Any>()
    private val closed = AtomicBoolean()
    private val lifecycle = Any()
    protected val isClosed: Boolean get() = closed.get()

    /** The ids of the players in the audience, as a live view that allocates nothing. */
    open val playerIds: Set<UUID> get() = memberIds
    open val players: List<Player> get() = memberIds.mapNotNull { server.getPlayer(it) }

    open fun displayState(player: Player): AudienceDisplayState {
        if (player.uniqueId in memberIds) return AudienceDisplayState.IN_AUDIENCE
        return AudienceDisplayState.NOT_CONSIDERED
    }

    open fun initialize() {
        if (isActive) return
        isActive = true
        server.pluginManager.registerEvents(this, plugin)
    }

    open fun dispose() {
        if (!isActive) return
        isActive = false
        players.forEach { removePlayer(it) }
        this.unregister()
    }

    fun addPlayer(player: Player) {
        if (closed.get()) return
        if (!Bukkit.isOwnedByCurrentRegion(player)) {
            foliaScheduler.atEntity(player, { addPlayer(player) }, null, 1L)
            return
        }
        synchronized(lifecycle) {
            if (closed.get() || player.uniqueId in memberIds) return
            if (!isActive) initialize()
            memberIds.add(player.uniqueId)
        }
        onPlayerAdd(player)
    }

    fun removePlayer(player: Player) {
        pendingTicks.remove(player.uniqueId)
        if (!Bukkit.isOwnedByCurrentRegion(player)) {
            foliaScheduler.atEntity(player, { removePlayer(player) }, { memberIds.remove(player.uniqueId) }, 1L)
            return
        }
        if (!memberIds.remove(player.uniqueId)) return
        onPlayerRemove(player)
        synchronized(lifecycle) {
            if (memberIds.isEmpty() && isActive) dispose()
        }
    }

    abstract fun onPlayerAdd(player: Player)
    abstract fun onPlayerRemove(player: Player)

    /** Coalesces each viewer's tick and checks membership again on the owning entity thread. */
    protected fun forEachPlayer(action: (Player) -> Unit) {
        players.forEach { tickPlayer(it, action) }
    }

    protected fun tickPlayer(player: Player, action: (Player) -> Unit) {
        if (closed.get() || player.uniqueId !in memberIds) return
        val token = Any()
        if (pendingTicks.putIfAbsent(player.uniqueId, token) != null) return
        val run = Runnable {
            try {
                if (!closed.get() && pendingTicks[player.uniqueId] === token && player.uniqueId in memberIds) {
                    action(player)
                }
            } finally {
                pendingTicks.remove(player.uniqueId, token)
            }
        }
        if (Bukkit.isOwnedByCurrentRegion(player)) run.run()
        else foliaScheduler.atEntity(player, run, { pendingTicks.remove(player.uniqueId, token) }, 1L)
    }

    /** Invalidates ticks for all displays before any member is moved during an unload. */
    fun beginClose() {
        closed.set(true)
        pendingTicks.clear()
    }

    /** Waits without blocking a region until each member has finished owner-context cleanup. */
    suspend fun close() {
        beginClose()
        val members = memberIds.mapNotNull { server.getPlayer(it) }
        for (player in members) {
            suspendCancellableCoroutine<Unit> { continuation ->
                val action = Runnable {
                    try {
                        removePlayer(player)
                        if (continuation.isActive) continuation.resume(Unit)
                    } catch (error: Throwable) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
                if (Bukkit.isOwnedByCurrentRegion(player)) action.run()
                else foliaScheduler.atEntity(player, action, {
                    memberIds.remove(player.uniqueId)
                    if (continuation.isActive) continuation.resume(Unit)
                }, 1L)
            }
        }
        memberIds.clear()
        dispose()
    }

    open operator fun contains(player: Player): Boolean = player.uniqueId in memberIds
    open operator fun contains(uuid: UUID): Boolean = uuid in memberIds
}

class PassThroughDisplay : AudienceDisplay() {
    override fun onPlayerAdd(player: Player) {}
    override fun onPlayerRemove(player: Player) {}
}


abstract class AudienceFilter(
    private val ref: Ref<out AudienceFilterEntry>
) : AudienceDisplay() {
    private val inverted = (ref.get() as? Invertible)?.inverted == true
    private val filteredPlayers: MutableSet<UUID> = Sets.newConcurrentHashSet()
    override val playerIds: Set<UUID> get() = filteredPlayers
    override val players: List<Player> get() = filteredPlayers.mapNotNull { server.getPlayer(it) }

    protected val consideredPlayers: List<Player> get() = super.players

    protected fun forEachConsideredPlayer(action: (Player) -> Unit) {
        consideredPlayers.forEach { tickPlayer(it, action) }
    }

    override fun displayState(player: Player): AudienceDisplayState {
        if (player in this) return AudienceDisplayState.IN_AUDIENCE
        if (canConsider(player)) return AudienceDisplayState.BLOCKED
        return AudienceDisplayState.NOT_CONSIDERED
    }

    abstract fun filter(player: Player): Boolean

    /**
     * Updates the filter for the player with the given value.
     * If the audience is inverted, the [isFiltered] will not align with the return value
     *
     * @return If the player is in the audience or not
     */
    fun Player.updateFilter(isFiltered: Boolean): Boolean {
        if (!Bukkit.isOwnedByCurrentRegion(this)) {
            foliaScheduler.atEntity(this, { updateFilter(isFiltered) }, null, 1L)
            return this in this@AudienceFilter
        }
        val allow = !inverted == isFiltered && canConsider(this)
        if (allow) {
            if (filteredPlayers.add(uniqueId)) {
                onPlayerFilterAdded(this)
                get<AudienceManager>(AudienceManager::class.java).addPlayerToChildren(this, ref)
            }
        } else {
            if (filteredPlayers.remove(uniqueId)) {
                onPlayerFilterRemoved(this)
                get<AudienceManager>(AudienceManager::class.java).removePlayerFromChildren(this, ref)
            }
        }
        return allow
    }

    /**
     * Refreshes the player's audience filter state.
     *
     * This method applies the logic defined by the `filter` function to determine the updated
     * filter state of the player and then applies the result using `updateFilter`. The filter state
     * defines whether a player should be included in or excluded from the audience.
     *
     * @return If the player is in the audience or not
     */
    fun Player.refresh(): Boolean {
        if (!Bukkit.isOwnedByCurrentRegion(this)) {
            foliaScheduler.atEntity(this, { refresh() }, null, 1L)
            return this in this@AudienceFilter
        }
        return updateFilter(filter(this))
    }

    override fun onPlayerAdd(player: Player) {
        player.refresh()
    }

    override fun onPlayerRemove(player: Player) {
        player.updateFilter(false)
    }

    open fun onPlayerFilterAdded(player: Player) {
    }

    open fun onPlayerFilterRemoved(player: Player) {
    }

    fun canConsider(player: Player): Boolean = !isClosed && super.contains(player)
    fun canConsider(uuid: UUID): Boolean = !isClosed && super.contains(uuid)

    override fun contains(player: Player): Boolean = contains(player.uniqueId)
    override fun contains(uuid: UUID): Boolean = uuid in filteredPlayers
}

class PassThroughFilter(
    ref: Ref<out AudienceFilterEntry>
) : AudienceFilter(ref) {
    override fun filter(player: Player): Boolean = true
}

