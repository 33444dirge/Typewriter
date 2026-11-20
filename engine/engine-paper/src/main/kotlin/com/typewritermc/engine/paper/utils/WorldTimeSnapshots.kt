package com.typewritermc.engine.paper.utils

import com.typewritermc.core.utils.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.bukkit.Bukkit

data class WorldTimeSnapshot(val time: Long, val fullTime: Long, val gameTime: Long)

/** World clocks belong to the global tick; region activities consume its last published sample. */
object WorldTimeSnapshots {
    @Volatile private var snapshots = emptyMap<String, WorldTimeSnapshot>()
    private var ticker: Job? = null

    suspend fun load() {
        clear()
        withContext(Dispatchers.Sync) {
            capture()
            ticker = Dispatchers.Sync.launch {
                while (isActive) {
                    delay(TICK_MS)
                    capture()
                }
            }
        }
    }

    operator fun get(worldId: String): WorldTimeSnapshot? = snapshots[worldId]

    suspend fun clear() {
        ticker?.cancelAndJoin()
        ticker = null
        snapshots = emptyMap()
    }

    private fun capture() {
        check(Bukkit.isGlobalTickThread()) { "World clocks must be captured on the global tick thread" }
        snapshots = Bukkit.getWorlds().associate { world ->
            world.uid.toString() to WorldTimeSnapshot(world.time, world.fullTime, world.gameTime)
        }
    }
}
