package com.typewritermc.engine.paper.entry.entity

import com.typewritermc.core.entries.Ref
import com.typewritermc.core.utils.point.Position
import com.typewritermc.engine.paper.entry.entries.*
import org.bukkit.entity.Player
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

class GroupAudienceEntityDisplay(
    override val instanceEntryRef: Ref<out EntityInstanceEntry>,
    override val creator: EntityCreator,
    private val activityCreators: ActivityCreator,
    private val suppliers: List<Pair<PropertySupplier<*>, Int>>,
    private val spawnPosition: Position,
    private val showRange: Var<Double> = ConstVar(entityShowRange),
    private val group: GroupEntry,
) : AudienceFilter(instanceEntryRef), TickableDisplay, AudienceEntityDisplay {
    private val activityManagers = ConcurrentHashMap<GroupId, ActivityManager<in SharedActivityContext>>()
    private val entities = ConcurrentHashMap<UUID, DisplayEntity>()
    private val memberships = ConcurrentHashMap<UUID, GroupId>()
    private data class DisplayMembership(
        val groupId: GroupId,
        val manager: ActivityManager<in SharedActivityContext>,
    )
    private val displayedMemberships = ConcurrentHashMap<UUID, DisplayMembership>()

    /**
     * When nobody can see the entity, but it is still active, there is no way to get the state for the entity.
     * So we just assume that the entity state stays the same.
     */
    private val lastStates = ConcurrentHashMap<GroupId, EntityState>()


    private fun groupViewers(groupId: GroupId): List<Player> {
        return players.filter { memberships[it.uniqueId] == groupId }
    }

    override fun filter(player: Player): Boolean {
        val groupId = group.snapshotGroupId(player) ?: GroupId(player.uniqueId)
        val previous = memberships.put(player.uniqueId, groupId)
        if (previous != groupId) {
            try {
                detachViewer(player)
            } finally {
                previous?.let(::releaseEmptyGroup)
            }
        }
        ensureGroup(groupId)
        if (player in this && !entities.containsKey(player.uniqueId)) attachViewer(player, groupId)
        val npcLocation = activityManagers[groupId]?.position ?: return false
        val distance = npcLocation.distanceSquared(player.location) ?: return false
        val showRange = showRange.get(player)
        return distance <= showRange * showRange
    }

    override fun onPlayerAdd(player: Player) {
        val groupId = group.snapshotGroupId(player) ?: GroupId(player.uniqueId)
        memberships[player.uniqueId] = groupId
        ensureGroup(groupId)

        super.onPlayerAdd(player)
    }

    private fun ensureGroup(groupId: GroupId) {
        activityManagers.computeIfAbsent(groupId) {
            val viewers = groupViewers(groupId)
            val context = SharedActivityContext(instanceEntryRef, viewers)
            val activity = activityCreators.create(context, spawnPosition.toProperty())
            val activityManager = ActivityManager(activity)
            activityManager.initialize(context)
            lastStates[groupId] = EntityState()
            activityManager
        }
    }

    override fun onPlayerFilterAdded(player: Player) {
        super.onPlayerFilterAdded(player)
        val groupId = memberships[player.uniqueId] ?: return
        attachViewer(player, groupId)
    }

    private fun attachViewer(player: Player, groupId: GroupId) {
        val activityManager = activityManagers[groupId] ?: return
        entities.computeIfAbsent(player.uniqueId) {
            DisplayEntity(player, creator, activityManager, suppliers.toCollectors())
        }
        displayedMemberships[player.uniqueId] = DisplayMembership(groupId, activityManager)
        activityManager.addedViewer(SharedActivityContext(instanceEntryRef, groupViewers(groupId)), player)
    }

    override fun tick() {
        forEachConsideredPlayer { it.refresh() }

        activityManagers.forEach { (groupId, manager) ->
            val viewers = groupViewers(groupId)

            // This is not an exact solution.
            // When the state is different between players, it might look weird.
            // But there is no real solution to this.
            // So we pick the first entity's state and use to try and keep the state consistent.
            val viewerId = viewers.firstOrNull()?.uniqueId
            val entityStateFromPlayer =
                if (viewerId != null) entities[viewerId]?.state?.also { lastStates[groupId] = it } else null
            val entityState = entityStateFromPlayer ?: lastStates.getOrPut(groupId) { EntityState() }

            val context = SharedActivityContext(instanceEntryRef, viewers, entityState)
            manager.tick(context)
        }

        entities.values.forEach { it.tick() }
    }

    override fun onPlayerFilterRemoved(player: Player) {
        detachViewer(player)
        super.onPlayerFilterRemoved(player)
    }

    private fun detachViewer(player: Player) {
        val membership = displayedMemberships.remove(player.uniqueId)
        try {
            membership?.manager?.removedViewer(
                SharedActivityContext(instanceEntryRef, groupViewers(membership.groupId)), player,
            )
        } finally {
            entities.remove(player.uniqueId)?.dispose()
        }
    }

    override fun onPlayerRemove(player: Player) {
        try {
            super.onPlayerRemove(player)
        } finally {
            memberships.remove(player.uniqueId)?.let(::releaseEmptyGroup)
        }
    }

    private fun releaseEmptyGroup(groupId: GroupId) {
        var retired: ActivityManager<in SharedActivityContext>? = null
        activityManagers.computeIfPresent(groupId) { _, manager ->
            if (memberships.containsValue(groupId)) manager
            else {
                retired = manager
                lastStates.remove(groupId)
                null
            }
        }
        retired?.let {
            it.dispose(SharedActivityContext(instanceEntryRef, emptyList()))
        }
    }

    override fun dispose() {
        super.dispose()
        entities.values.forEach { it.dispose() }
        entities.clear()
        activityManagers.forEach { (groupId, manager) ->
            manager.dispose(SharedActivityContext(instanceEntryRef, groupViewers(groupId)))
        }
        activityManagers.clear()
        lastStates.clear()
        memberships.clear()
        displayedMemberships.clear()
    }

    override fun playerSeesEntity(playerId: UUID, entityId: Int): Boolean {
        return entities[playerId]?.contains(entityId) == true
    }

    override fun position(playerId: UUID): Position? {
        val groupId = memberships[playerId] ?: return null
        return activityManagers[groupId]?.position?.toPosition()
    }

    override fun entityState(playerId: UUID): EntityState {
        entities[playerId]?.state?.let { return it }
        val groupId = memberships[playerId] ?: return EntityState()
        lastStates[groupId]?.let { return it }
        return EntityState()
    }

    override fun <P : EntityProperty> property(playerId: UUID, type: KClass<P>): P? = entities[playerId]?.property(type)

    override fun canView(playerId: UUID): Boolean = canConsider(playerId)
    override fun isSpawnedIn(playerId: UUID): Boolean = entities[playerId] != null

    override fun entityId(playerId: UUID): Int {
        return entities[playerId]?.entityId ?: 0
    }
}
