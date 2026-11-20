package com.typewritermc.roadnetwork.pathfinding.pathetic

import com.typewritermc.core.utils.UntickedAsync
import com.typewritermc.engine.paper.snippets.snippet
import com.typewritermc.engine.paper.utils.BlockSnapshots
import com.typewritermc.engine.paper.utils.BlockView
import de.bsommerfeld.pathetic.api.pathing.Pathfinder
import de.bsommerfeld.pathetic.api.pathing.context.EnvironmentContext
import de.bsommerfeld.pathetic.api.pathing.result.PathfinderResult
import de.bsommerfeld.pathetic.api.provider.NavigationPoint
import de.bsommerfeld.pathetic.api.provider.NavigationPointProvider
import de.bsommerfeld.pathetic.api.wrapper.PathPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.bukkit.Location
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private val maxSnapshotBlocks by snippet(
    "road-network.pathfinding.max-snapshot-blocks", 262144,
    "单次寻路可采集的方块上限（64–1048576）；超限时终止该次寻路，避免占满内存。",
)

private val concurrentSearches by snippet(
    "road-network.pathfinding.concurrent-searches", 2,
    "同时采集快照与计算的寻路数量（1–32），用于限制并发内存峰值；修改后重启生效。",
)
private val waitingSearches by snippet(
    "road-network.pathfinding.waiting-searches", 64,
    "等待寻路名额的请求上限（0–4096），满时本次返回无路径，下次刷新可重试；修改后重启生效。",
)
private val searchTimeoutMillis by snippet(
    "road-network.pathfinding.timeout-millis", 30000,
    "每次寻路连同等待名额的最长毫秒数（1000–120000），超时取消并释放资源。",
)
private val searchBudget by lazy {
    PathSearchBudget(concurrentSearches.coerceIn(1, 32), waitingSearches.coerceIn(0, 4096))
}

class SnapshotEnvironmentContext(val world: BlockView, val worldId: String = "snapshot") : EnvironmentContext

class SnapshotPointProvider : NavigationPointProvider {
    override fun getNavigationPoint(position: PathPosition, context: EnvironmentContext): NavigationPoint {
        val snapshots = (context as SnapshotEnvironmentContext).world
        return NavigationPoint {
            snapshots !is BlockSnapshots || position.flooredY in snapshots.minHeight until snapshots.maxHeight
        }
    }
}

/** Executes searches only on detached blocks, collecting missing cells on their owning regions. */
suspend fun Pathfinder.findSnapshotPath(
    start: PathPosition,
    target: PathPosition,
    origin: Location,
): PathfinderResult? = withContext(Dispatchers.UntickedAsync) {
    withTimeoutOrNull(searchTimeoutMillis.coerceIn(1000, 120000).toLong()) {
        searchBudget.run { searchSnapshotPath(start, target, origin) }
    }
}

private suspend fun Pathfinder.searchSnapshotPath(
    start: PathPosition,
    target: PathPosition,
    origin: Location,
): PathfinderResult? {
    val snapshots = BlockSnapshots.create(origin, maxSnapshotBlocks)
    val environment = SnapshotEnvironmentContext(snapshots, snapshots.worldId)
    while (true) {
        currentCoroutineContext().ensureActive()
        val result = suspendCancellableCoroutine<PathfinderResult?> { continuation ->
            val search = findPath(start, target, environment)
                .ifPresent { continuation.resume(it) }
                .orElse { continuation.resume(null) }
                .exceptionally { continuation.resumeWithException(it) }
            continuation.invokeOnCancellation { search.abort() }
        }
        currentCoroutineContext().ensureActive()
        if (!snapshots.hasMissing) return result
        snapshots.loadMissing()
    }
}
