package com.typewritermc.roadnetwork.entries

import com.typewritermc.core.entries.Ref
import com.typewritermc.core.entries.priority
import com.typewritermc.core.entries.ref
import com.typewritermc.core.extension.annotations.Default
import com.typewritermc.core.extension.annotations.Help
import com.typewritermc.core.extension.annotations.Tags
import com.typewritermc.core.utils.UntickedAsync
import com.typewritermc.core.utils.launch
import com.typewritermc.core.utils.point.Position
import com.typewritermc.core.utils.point.distanceSquared
import com.typewritermc.engine.paper.entry.descendants
import com.typewritermc.engine.paper.entry.entries.AudienceDisplay
import com.typewritermc.engine.paper.entry.entries.AudienceFilterEntry
import com.typewritermc.engine.paper.entry.entries.PassThroughFilter
import com.typewritermc.engine.paper.entry.entries.TickableDisplay
import com.typewritermc.engine.paper.entry.inAudience
import com.typewritermc.engine.paper.snippets.snippet
import com.typewritermc.engine.paper.utils.firstWalkableLocationBelowAsync
import com.typewritermc.engine.paper.utils.entityDispatcher
import com.typewritermc.engine.paper.utils.foliaScheduler
import com.typewritermc.engine.paper.utils.position
import com.typewritermc.engine.paper.utils.toBukkitWorld
import com.typewritermc.engine.paper.utils.toPosition
import com.typewritermc.roadnetwork.RoadNetworkEntry
import com.typewritermc.roadnetwork.gps.GPSEdge
import com.typewritermc.roadnetwork.gps.PointToPointGPS
import com.typewritermc.roadnetwork.gps.findPathBetweenPosition
import com.typewritermc.roadnetwork.pathfinding.pathetic.PathCalculationResult
import com.typewritermc.roadnetwork.roadNetworkMaxDistance
import de.bsommerfeld.pathetic.bukkit.mapper.BukkitMapper
import kotlinx.coroutines.*
import org.bukkit.entity.Player
import org.koin.core.component.KoinComponent
import java.time.Duration
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

class SinglePathStreamDisplay(
    private val roadNetwork: Ref<RoadNetworkEntry>,
    private val display: (Player) -> Ref<PathStreamDisplayEntry>,
    private val startPosition: (Player) -> Position = Player::position,
    private val endPosition: (Player) -> Position,
) : AudienceDisplay(), TickableDisplay {
    private val producers = ConcurrentHashMap<UUID, PathStreamProducer>()
    override fun onPlayerAdd(player: Player) {
        val entry = display(player).get()
        producers[player.uniqueId] =
            entry?.createProducer(player, roadNetwork, startPosition, endPosition) ?: LinePathStreamProducer(
                player = player,
                roadNetwork = roadNetwork,
                startPosition = startPosition,
                endPosition = endPosition
            )
    }

    override fun onPlayerRemove(player: Player) {
        producers.remove(player.uniqueId)?.dispose()
    }

    override fun tick() {
        forEachPlayer { player ->
            producers.computeIfPresent(player.uniqueId) { _, producer ->
                val entry = this.display(player).get()
                val id = entry?.id ?: ""
                if (producer.id == id) {
                    producer.tick()
                    return@computeIfPresent producer
                }

                producer.dispose()
                (entry?.createProducer(player, roadNetwork, startPosition, endPosition) ?: LinePathStreamProducer(
                    player = player,
                    roadNetwork = roadNetwork,
                    startPosition = startPosition,
                    endPosition = endPosition
                )).apply { tick() }
            }
        }
    }
}

class MultiPathStreamDisplay(
    private val ref: Ref<RoadNetworkEntry>,
    private val streams: (Player) -> List<StreamProducer>,
) : AudienceDisplay(), TickableDisplay {
    private val producers = ConcurrentHashMap<UUID, MutableMap<String, PathStreamProducer>>()

    override fun tick() {
        forEachPlayer { player ->
            producers.computeIfPresent(player.uniqueId) { _, displays ->
                val streams = streams(player).associateBy { it.id }
                streams.filter { it.key !in displays }.forEach { (key, stream) ->
                    displays[key] = stream.createProducer(ref, player)
                }

                streams.mapNotNull {
                    val display = displays[it.key] ?: return@mapNotNull null
                    it.key to (it.value to display)
                }.forEach { (key, value) ->
                    val (stream, display) = value

                    if (stream.ref.id == display.id) {
                        display.tick()
                        return@forEach
                    }
                    display.dispose()
                    displays[key] = stream.createProducer(ref, player).apply { tick() }
                }

                displays.keys.filter { it !in streams }.forEach { key ->
                    displays.remove(key)?.dispose()
                }
                displays
            }
        }
    }

    override fun onPlayerAdd(player: Player) {
        producers.putIfAbsent(player.uniqueId, mutableMapOf())
    }

    override fun onPlayerRemove(player: Player) {
        producers.remove(player.uniqueId)?.forEach { it.value.dispose() }
    }
}

class StreamProducer(
    val id: String,
    val ref: Ref<PathStreamDisplayEntry>,
    val startPosition: (Player) -> Position = Player::position,
    val endPosition: (Player) -> Position,
) {
    fun createProducer(roadNetwork: Ref<RoadNetworkEntry>, player: Player): PathStreamProducer {
        return ref.get()?.createProducer(player, roadNetwork, startPosition, endPosition) ?: LinePathStreamProducer(
            player = player,
            roadNetwork = roadNetwork,
            startPosition = startPosition,
            endPosition = endPosition
        )
    }
}

@Tags("path_stream_display")
interface PathStreamDisplayEntry : AudienceFilterEntry {
    @Default("1700")
    @Help("The time between a new stream being calculated")
    val refreshDuration: Duration

    fun createDisplay(
        player: Player,
    ): PathStreamDisplay?

    fun createProducer(
        player: Player,
        roadNetwork: Ref<RoadNetworkEntry>,
        startPosition: (Player) -> Position,
        endPosition: (Player) -> Position,
    ): PathStreamProducer

    fun displays(): List<Ref<PathStreamDisplayEntry>> = children.descendants(PathStreamDisplayEntry::class) + ref()

    override suspend fun display() = PassThroughFilter(ref())
}

fun List<Ref<PathStreamDisplayEntry>>.highestPathStreamDisplayEntry(
    player: Player,
): Ref<PathStreamDisplayEntry>? = filter { player.inAudience(it) }.maxByOrNull { it.priority }

interface PathStreamDisplay {
    fun display(position: Position)
    fun dispose()

    /** Releases private resources after viewer retirement without accessing live state or sending packets. */
    fun retireResources() {}
}


private val pathStreamTimeout by snippet(
    "road_network.path_stream.timeout",
    15_000,
    "When path streams are forcefully canceled after this amount of time in milliseconds"
)

abstract class PathStream(
    val displays: List<PathStreamDisplay>
) {
    protected var startTime: Long = System.currentTimeMillis()

    fun tick(): Boolean {
        val position = forwardPath()
        displays.forEach { it.display(position) }
        return shouldContinue() && System.currentTimeMillis() - startTime < pathStreamTimeout
    }

    abstract fun forwardPath(): Position
    abstract fun shouldContinue(): Boolean

    open fun dispose() = cleanup(true)

    fun retireResources() = cleanup(false)

    private fun cleanup(onOwner: Boolean) {
        var failure: Throwable? = null
        fun cleanup(action: () -> Unit) {
            try {
                action()
            } catch (error: Throwable) {
                if (failure == null) failure = error
                else if (failure !== error) failure!!.addSuppressed(error)
            }
        }
        displays.forEach { cleanup(if (onOwner) it::dispose else it::retireResources) }
        cleanup(::disposeResources)
        failure?.let { throw it }
    }

    /** Cancels calculation resources after viewer retirement; must not access live Bukkit state. */
    open fun disposeResources() {}
}

typealias PathStreamDisplaysSupplier = (Player) -> List<PathStreamDisplay>

fun List<Ref<PathStreamDisplayEntry>>.createDisplays(player: Player): List<PathStreamDisplay> = mapNotNull {
    if (!player.inAudience(it)) return@mapNotNull null
    val entry = it.get() ?: return@mapNotNull null
    entry.createDisplay(player)
}

abstract class PathStreamProducer(
    val player: Player,
    val id: String,
    protected val roadNetwork: Ref<RoadNetworkEntry>,
    protected val startPosition: (Player) -> Position,
    protected val endPosition: (Player) -> Position,
    protected val refreshDuration: Duration,
    protected val displaySupplier: PathStreamDisplaysSupplier,
) : KoinComponent {

    protected val gps = PointToPointGPS(
        roadNetwork,
        {
            val position = withContext(player.entityDispatcher) { startPosition(player) }
            position.firstWalkableLocationBelowAsync() ?: position
        },
        {
            val position = withContext(player.entityDispatcher) { endPosition(player) }
            position.firstWalkableLocationBelowAsync() ?: position
        },
    )

    protected var lastRefresh = 0L
    @Volatile protected var job: Job? = null
    private val disposed = AtomicBoolean()
    private val pendingTick = AtomicBoolean()
    protected val streams = mutableListOf<PathStream>()

    abstract suspend fun refreshPath(): PathStream?

    fun tick() {
        if (disposed.get()) return
        if (!foliaScheduler.owns(player)) {
            if (!pendingTick.compareAndSet(false, true)) return
            foliaScheduler.atEntity(player, {
                pendingTick.set(false)
                tick()
            }, { pendingTick.set(false) }, 1L)
            return
        }
        if (job?.isActive == false) {
            lastRefresh = System.currentTimeMillis()
            job = null
        }
        if (job == null && (System.currentTimeMillis() - lastRefresh) > refreshDuration.toMillis()) {
            job = player.entityDispatcher.launch {
                withTimeout(30.seconds) {
                    val stream = refreshPath() ?: return@withTimeout
                    synchronized(streams) {
                        if (disposed.get()) stream.dispose()
                        else streams.add(stream)
                    }
                }
            }
        }

        synchronized(streams) {
            if (disposed.get()) return
            val iterator = streams.iterator()
            while (iterator.hasNext()) {
                val stream = iterator.next()
                val retain = try {
                    stream.tick()
                } catch (error: Exception) {
                    error.printStackTrace()
                    false
                }
                if (!retain) {
                    iterator.remove()
                    stream.dispose()
                }
            }
        }
    }

    open fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        job?.cancel()
        job = null
        if (foliaScheduler.owns(player)) disposeStreams(true)
        else foliaScheduler.atEntity(player, { disposeStreams(true) }, { disposeStreams(false) }, 1L)
    }

    private fun disposeStreams(onOwner: Boolean) {
        val previous = synchronized(streams) { streams.toList().also { streams.clear() } }
        previous.forEach { stream ->
            try {
                if (onOwner) stream.dispose() else stream.retireResources()
            } catch (error: Exception) {
                error.printStackTrace()
            }
        }
    }

    suspend fun calculatePathing(): Pair<List<GPSEdge>, List<List<Position>>>? {
        val (start, end) = points() ?: return null
        val edges = findEdges()
        val visibleEdges = edges.filterVisible(start, end)
        val path = findPaths(visibleEdges)
        return edges to path
    }

    suspend fun points(): Pair<Position, Position>? {
        val start = withContext(player.entityDispatcher) { startPosition(player) }.firstWalkableLocationBelowAsync() ?: return null
        val end = withContext(player.entityDispatcher) { endPosition(player) }.firstWalkableLocationBelowAsync() ?: return null

        // When the start and end location are the same, we don't need to find a path.
        if ((start.distanceSquared(end) ?: Double.MAX_VALUE) < 1) {
            return null
        }
        return start to end
    }

    suspend fun findEdges(): List<GPSEdge> {
        return gps.findPath().getOrElse { emptyList() }
    }

    fun List<GPSEdge>.filterVisible(
        start: Position,
        end: Position,
    ) = filter {
        ((it.start.distanceSquared(start) ?: Double.MAX_VALUE) < roadNetworkMaxDistance * roadNetworkMaxDistance
                || (it.end.distanceSquared(start)
            ?: Double.MAX_VALUE) < roadNetworkMaxDistance * roadNetworkMaxDistance)
                ||
                ((it.start.distanceSquared(end) ?: Double.MAX_VALUE) < roadNetworkMaxDistance * roadNetworkMaxDistance
                        || (it.end.distanceSquared(end)
                    ?: Double.MAX_VALUE) < roadNetworkMaxDistance * roadNetworkMaxDistance)
    }

    suspend fun findPaths(
        edges: List<GPSEdge>,
    ): List<List<Position>> = coroutineScope {
        edges
            .map { edge ->
                async {
                    findPath(edge.start, edge.end)
                }
            }
            .awaitAll()
            .map { positions -> positions.map { it.mid() } }
    }


    private suspend fun findPath(
        start: Position,
        end: Position,
    ): Iterable<Position> {
        return when (val pathResult =
            findPathBetweenPosition(start, end, roadNetworkRef = gps.roadNetwork, checkIntermediateNodes = false, allowFallback = true)) {
            is PathCalculationResult.Success -> {
                pathResult.path.map {
                    BukkitMapper.toLocation(
                        it, pathResult.world.toBukkitWorld()
                    ).toPosition()
                }
            }

            is PathCalculationResult.Failure -> {
                emptyList()
            }
        }
    }
}
