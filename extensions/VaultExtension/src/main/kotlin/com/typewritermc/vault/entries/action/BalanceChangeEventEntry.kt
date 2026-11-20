package com.typewritermc.vault.entries.action

import com.typewritermc.core.books.pages.Colors
import com.typewritermc.core.entries.Query
import com.typewritermc.core.entries.Ref
import com.typewritermc.core.extension.Initializable
import com.typewritermc.core.extension.annotations.Entry
import com.typewritermc.core.extension.annotations.KeyType
import com.typewritermc.core.extension.annotations.Singleton
import com.typewritermc.core.interaction.EntryContextKey
import com.typewritermc.core.utils.UntickedAsync
import com.typewritermc.core.utils.launch
import com.typewritermc.engine.paper.entry.TriggerableEntry
import com.typewritermc.engine.paper.entry.entries.EventEntry
import com.typewritermc.engine.paper.entry.triggerAllFor
import com.typewritermc.engine.paper.plugin
import com.typewritermc.engine.paper.utils.server
import com.typewritermc.vault.VaultInitializer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import lirand.api.extensions.events.unregister
import lirand.api.extensions.server.registerEvents
import net.milkbowl.vault.economy.Economy
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import com.typewritermc.engine.paper.utils.entityDispatcher
import kotlinx.coroutines.isActive
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

@Entry(
    "balance_change_event",
    "Triggers when the player's balance changes",
    Colors.YELLOW,
    "fluent:wallet-credit-card-28-filled"
)
/**
 * The `Balance Change Event` entry is an event triggered when the player's balance changes.
 *
 * ## How could this be used?
 * This could be used to give the player a custom role when they reach a certain balance.
 * For example, they could be given the role of "Gold Member" when they reach 100$.
 */
class BalanceChangeEventEntry(
    override val id: String = "",
    override val name: String = "",
    override val triggers: List<Ref<TriggerableEntry>> = emptyList(),
) : EventEntry

enum class BalanceChangeContextKeys(override val klass: KClass<*>) : EntryContextKey {
    @KeyType(Double::class)
    PREVIOUS_BALANCE(Double::class),

    @KeyType(Double::class)
    NEW_BALANCE(Double::class),

    @KeyType(Int::class)
    PREVIOUS_AMOUNT(Int::class),

    @KeyType(Int::class)
    NEW_AMOUNT(Int::class),
}

@Singleton
class BalanceLoop : Initializable, Listener, KoinComponent {
    private val jobs = ConcurrentHashMap<UUID, Job>()
    @Volatile private var entries = emptyList<BalanceChangeEventEntry>()
    @Volatile private var running = false
    private val vaultInitializer: VaultInitializer by inject()
    private val cachedBalance = ConcurrentHashMap<UUID, Double>()

    override suspend fun initialize() {
        val entries = Query.find<BalanceChangeEventEntry>().toList()
        if (entries.isEmpty()) return
        this.entries = entries
        running = true
        plugin.registerEvents(this)
        server.onlinePlayers.forEach(::start)
    }

    private fun start(player: Player) {
        if (!running) return
        jobs.computeIfAbsent(player.uniqueId) {
            player.entityDispatcher.launch {
                vaultInitializer.economy?.let { economy ->
                    cachedBalance[player.uniqueId] = economy.getBalance(player)
                }
                while (isActive && running) {
                    delay(100)
                    val economy = vaultInitializer.economy ?: continue
                    if (isActive && running) player.checkBalance(economy, entries)
                }
            }
        }
    }

    private fun Player.checkBalance(
        economy: Economy,
        entries: List<BalanceChangeEventEntry>
    ) {
        val balance = economy.getBalance(this)
        val oldBalance = cachedBalance.put(uniqueId, balance) ?: return
        if (balance == oldBalance) return

        if (!running) return
        entries.triggerAllFor(this) {
            BalanceChangeContextKeys.PREVIOUS_BALANCE withValue oldBalance
            BalanceChangeContextKeys.NEW_BALANCE withValue balance
            BalanceChangeContextKeys.PREVIOUS_AMOUNT withValue oldBalance.toInt()
            BalanceChangeContextKeys.NEW_AMOUNT withValue balance.toInt()
        }
    }

    @EventHandler
    private fun onPlayerJoin(event: PlayerJoinEvent) {
        start(event.player)
    }

    @EventHandler
    private fun onPlayerQuit(event: PlayerQuitEvent) {
        val player = event.player
        jobs.remove(player.uniqueId)?.cancel()
        cachedBalance.remove(player.uniqueId)
    }

    override suspend fun shutdown() {
        running = false
        unregister()
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        entries = emptyList()
        cachedBalance.clear()
    }

}
