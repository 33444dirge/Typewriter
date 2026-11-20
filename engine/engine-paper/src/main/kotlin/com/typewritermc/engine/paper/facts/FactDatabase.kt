package com.typewritermc.engine.paper.facts

import com.typewritermc.core.entries.Query
import com.typewritermc.core.entries.Ref
import com.typewritermc.core.entries.ref
import com.typewritermc.core.interaction.InteractionContext
import com.typewritermc.core.interaction.context
import com.typewritermc.core.utils.UntickedAsync
import com.typewritermc.core.utils.launch
import com.typewritermc.engine.paper.entry.Modifier
import com.typewritermc.engine.paper.entry.ModifierOperator
import com.typewritermc.engine.paper.entry.entries.*
import com.typewritermc.engine.paper.entry.triggerFor
import com.typewritermc.engine.paper.interaction.interactionContext
import com.typewritermc.engine.paper.plugin
import com.typewritermc.engine.paper.utils.logErrorIfNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.bukkit.entity.Player
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.ConcurrentHashMap

private const val FACT_STORAGE_DELAY = 60 * 3

class FactDatabase : KoinComponent {
    private val storage: FactStorage by inject()

    // Local stored version of player facts
    private val cache = ConcurrentHashMap<FactId, FactData>()
    private val persistenceMutex = Mutex()
    private var maintenanceJob: Job? = null

    suspend fun initialize() {
        withContext(Dispatchers.IO) {
            storage.init()
            loadFactsFromPersistentStorage()
        }

        // Filter expired facts every second.
        // After that, save the facts of the players who have facts that expired or changed.
        maintenanceJob = Dispatchers.UntickedAsync.launch {
            var cycle = 1
            while (isActive && plugin.isEnabled) {
                delay(1000)
                removeExpiredFacts()

                if (cycle++ % FACT_STORAGE_DELAY == 0) {
                    storeFactsInPersistentStorage()
                }
            }
        }
    }

    suspend fun shutdown() {
        maintenanceJob?.cancelAndJoin()
        maintenanceJob = null
        withContext(Dispatchers.IO) {
            storeFactsInPersistentStorage()
            storage.shutdown()
        }
    }

    private suspend fun loadFactsFromPersistentStorage() {
        val facts = storage.loadFacts()
        cache.clear()
        cache.putAll(facts)
    }

    private suspend fun storeFactsInPersistentStorage() = persistenceMutex.withLock {
        val entries = Query.find<PersistableFactEntry>().associateBy { it.id }
        val facts = ArrayList<Pair<FactId, FactData>>()
        cache.forEach { (id, data) ->
            val entry = entries[id.entryId] ?: return@forEach
            if (!entry.canPersist(id, data)) return@forEach
            if (entry is ExpirableFactEntry && entry.hasExpired(id, data)) return@forEach
            facts.add(id to data)
        }
        storage.storeFacts(facts)
    }

    private fun removeExpiredFacts() {
        val entries = Query.find<ExpirableFactEntry>().associateBy { it.id }
        if (entries.isEmpty()) return
        cache.forEach { (id, data) ->
            val entry = entries[id.entryId] ?: return@forEach
            if (entry.hasExpired(id, data)) cache.remove(id, data)
        }
    }

    internal operator fun get(id: FactId): FactData? = cache[id]

    internal operator fun set(id: FactId, data: FactData) {
        if (data.value == 0) {
            cache.remove(id)
        } else {
            cache[id] = data
        }
    }

    /** The transform must only use immutable fact values; it runs inside a per-key atomic update. */
    internal fun update(id: FactId, transform: (FactData) -> FactData): FactChange {
        lateinit var change: FactChange
        cache.compute(id) { _, previous ->
            val before = previous ?: FactData(0)
            val after = transform(before)
            change = FactChange(before, after)
            after.takeIf { it.value != 0 }
        }
        return change
    }

    fun modify(player: Player, modifiers: List<Modifier>, context: InteractionContext? = player.interactionContext) {
        modifiers.forEach { modifier ->
            val cached = modifier.fact.get() as? CachableFactEntry
            if (cached != null) {
                val factId = cached.identifier(player) ?: return@forEach
                val operand = modifier.value.get(player, context)
                update(factId) { current ->
                    if (!cached.canCache(factId, current)) current
                    else FactData(when (modifier.operator) {
                        ModifierOperator.ADD -> current.value + operand
                        ModifierOperator.SUBTRACT -> current.value - operand
                        ModifierOperator.MULTIPLY -> current.value * operand
                        ModifierOperator.SET -> operand
                    })
                }
                RefreshFactTrigger(cached.ref()).triggerFor(player, context())
                return@forEach
            }
            val value = when (modifier.operator) {
                    ModifierOperator.ADD, ModifierOperator.SUBTRACT -> {
                        val entry =
                            modifier.fact.get().logErrorIfNull("Could not find ${modifier.fact}") ?: return@forEach

                        if (entry !is ReadableFactEntry) {
                            plugin.logger.warning("Tried to add to a non-readable fact: ${modifier.fact}, how do you expect to add if you can't read?")
                            return@forEach
                        }

                        val fact = entry.readForPlayersGroup(player)
                        val change =
                            modifier.value.get(player, context)

                        if (modifier.operator == ModifierOperator.ADD) {
                            fact.value + change
                        } else {
                            fact.value - change
                        }
                    }

                    ModifierOperator.MULTIPLY -> {
                        val entry =
                            modifier.fact.get().logErrorIfNull("Could not find ${modifier.fact}") ?: return@forEach

                        if (entry !is ReadableFactEntry) {
                            plugin.logger.warning("Tried to multiply a non-readable fact: ${modifier.fact}, how do you expect to multiply if you can't read?")
                            return@forEach
                        }

                        val fact = entry.readForPlayersGroup(player)
                        modifier.value.get(player, context) * fact.value
                    }

                    ModifierOperator.SET -> modifier.value.get(player, context)
            }
            modify(player, mapOf(modifier.fact.id to value))
        }
    }

    fun modify(player: Player, modifier: FactsModifier.() -> Unit) {
        val modifications = FactsModifier().apply(modifier).build()
        modify(player, modifications)
    }

    fun modify(player: Player, modifications: Map<String, Int>) {
        if (modifications.isEmpty()) return

        for ((id, value) in modifications) {
            val entry = Query.findById<WritableFactEntry>(id) ?: continue
            entry.write(player, value)
            if (entry is ReadableFactEntry) {
                RefreshFactTrigger(entry.ref()).triggerFor(player, context())
            }
        }
    }
}

class FactsModifier {
    private val modifications = mutableMapOf<String, Int>()

    operator fun set(ref: Ref<out WritableFactEntry>, value: Int) = set(ref.id, value)

    operator fun set(id: String, value: Int) {
        modifications[id] = value
    }

    fun build(): Map<String, Int> = modifications
}

class RefreshFactTrigger(
    val fact: Ref<ReadableFactEntry>,
) : EventTrigger {
    override val id: String
        get() = "fact.${fact.id}"
}

fun Player.fact(ref: Ref<out ReadableFactEntry>) = ref.get()?.readForPlayersGroup(this)
