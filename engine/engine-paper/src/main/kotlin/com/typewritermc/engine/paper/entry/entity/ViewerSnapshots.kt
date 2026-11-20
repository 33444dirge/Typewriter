package com.typewritermc.engine.paper.entry.entity

import com.typewritermc.core.entries.Query
import com.typewritermc.core.entries.Ref
import com.typewritermc.engine.paper.entry.dialogue.currentDialogue
import com.typewritermc.engine.paper.entry.dialogue.speakersInDialogue
import com.typewritermc.engine.paper.entry.entries.DialogueEntry
import com.typewritermc.engine.paper.entry.entries.GroupEntry
import com.typewritermc.engine.paper.entry.entries.GroupId
import com.typewritermc.engine.paper.entry.entries.SpeakerEntry
import com.typewritermc.engine.paper.entry.entries.ReadableFactEntry
import com.typewritermc.engine.paper.entry.entries.CachableFactEntry
import com.typewritermc.engine.paper.facts.FactData
import com.typewritermc.engine.paper.logger
import com.typewritermc.engine.paper.utils.foliaScheduler
import com.typewritermc.engine.paper.utils.isLookable
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class ViewerSnapshot(
    val position: PositionProperty,
    val eyePosition: PositionProperty,
    val isLookable: Boolean,
    val dialogue: DialogueEntry?,
    val speakers: Set<Ref<SpeakerEntry>>,
    val groups: Map<String, GroupId?>,
    val facts: Map<String, FactData>,
)

/** One owner-published snapshot per active player session; contains no live player reference. */
object ViewerSnapshots {
    class Lease internal constructor() {
        @Volatile internal var snapshot: ViewerSnapshot? = null
        internal val errors: MutableSet<String> = ConcurrentHashMap.newKeySet()
    }
    private class Configuration(
        val groups: List<GroupEntry> = emptyList(),
        val facts: Map<String, ReadableFactEntry> = emptyMap(),
    ) {
        val requested: MutableSet<String> = ConcurrentHashMap.newKeySet()
    }
    private val viewers = ConcurrentHashMap<UUID, Lease>()
    @Volatile private var configuration = Configuration()

    fun initialize() {
        clear()
        val next = Configuration(
            Query.find<GroupEntry>().toList(),
            Query.find<ReadableFactEntry>().filter { it !is CachableFactEntry }.associateBy { it.id },
        )
        next.facts.values.filter { it.group.get() != null }.forEach { next.requested += it.id }
        configuration = next
    }

    fun open(playerId: UUID): Lease = Lease().also { viewers[playerId] = it }

    fun capture(player: Player, lease: Lease) {
        check(foliaScheduler.owns(player)) { "Viewer snapshots must be captured on the player owner" }
        val playerId = player.uniqueId
        if (viewers[playerId] !== lease) return
        val config = configuration
        val reported = lease.errors
        val groups = config.groups.associate { group ->
            group.id to try {
                group.groupId(player).also { reported.remove("group:${group.id}") }
            } catch (error: Exception) {
                if (reported.add("group:${group.id}")) {
                    logger.log(java.util.logging.Level.WARNING, "Failed to capture Typewriter group ${group.id}", error)
                }
                null
            }
        }
        val facts = config.requested.mapNotNull { config.facts[it] }.associate { fact ->
            fact.id to try {
                fact.readSinglePlayer(player).also { reported.remove("fact:${fact.id}") }
            } catch (error: Exception) {
                if (reported.add("fact:${fact.id}")) {
                    logger.log(java.util.logging.Level.WARNING, "Failed to capture Typewriter fact ${fact.id}", error)
                }
                FactData(0)
            }
        }
        lease.snapshot = ViewerSnapshot(
            player.location.toProperty(), player.eyeLocation.toProperty(), player.isLookable,
            player.currentDialogue, player.speakersInDialogue.toSet(), groups, facts,
        )
    }

    operator fun get(playerId: UUID): ViewerSnapshot? = viewers[playerId]?.snapshot
    fun readFact(fact: ReadableFactEntry, player: Player): FactData {
        if (foliaScheduler.owns(player)) return fact.readSinglePlayer(player)
        val config = configuration
        if (config.facts[fact.id] !== fact) return FactData(0)
        config.requested += fact.id
        return viewers[player.uniqueId]?.snapshot?.facts?.get(fact.id) ?: FactData(0)
    }

    fun remove(playerId: UUID, lease: Lease) {
        viewers.remove(playerId, lease)
    }

    fun clear() {
        viewers.clear()
        configuration = Configuration()
    }
}

val Player.viewerSnapshot: ViewerSnapshot? get() = ViewerSnapshots[uniqueId]

/** Other regions use the last owner sample instead of evaluating arbitrary group variables. */
fun GroupEntry.snapshotGroupId(player: Player): GroupId? =
    if (foliaScheduler.owns(player)) groupId(player) else player.viewerSnapshot?.groups?.get(id)
