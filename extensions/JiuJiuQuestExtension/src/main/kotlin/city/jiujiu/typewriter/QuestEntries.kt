package city.jiujiu.typewriter

import city.jiujiu.welfare.api.QuestRewardLedger
import city.jiujiu.welfare.api.WelfareLedger
import city.jiujiu.welfare.api.WelfareSettlementEvent
import com.typewritermc.core.entries.Query
import com.typewritermc.core.entries.Ref
import com.typewritermc.core.entries.emptyRef
import com.typewritermc.core.extension.annotations.Entry
import com.typewritermc.core.extension.annotations.EntryListener
import com.typewritermc.core.interaction.context
import com.typewritermc.engine.paper.entry.Criteria
import com.typewritermc.engine.paper.entry.Modifier
import com.typewritermc.engine.paper.entry.TriggerableEntry
import com.typewritermc.engine.paper.entry.entries.*
import com.typewritermc.engine.paper.entry.triggerAllFor
import com.typewritermc.engine.paper.facts.FactData
import com.typewritermc.engine.paper.utils.foliaScheduler
import com.typewritermc.core.extension.Initializable
import com.typewritermc.core.extension.annotations.Singleton
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CompletableFuture
import java.util.UUID
import org.koin.java.KoinJavaComponent.get
import java.util.concurrent.TimeUnit
import net.luckperms.api.LuckPermsProvider
import net.luckperms.api.node.Node
import org.bukkit.Bukkit
import org.bukkit.entity.Player

@Singleton
class StoryRuntime : Initializable {
    @Volatile var tasks: StorySession? = null
        private set
    override suspend fun initialize() { tasks = StorySession() }
    override suspend fun shutdown() { val old = tasks; tasks = null; old?.close() }
}

/** Cancels only this extension's callbacks; the shared engine scheduler belongs to Typewriter. */
class StorySession(
    private val owns: (Player) -> Boolean = { Bukkit.isOwnedByCurrentRegion(it) },
    private val dispatch: (Player, Runnable, Runnable) -> ScheduledTask? = { p, a, r ->
        foliaScheduler.atEntity(p, a, r, 1L)
    },
) : AutoCloseable {
    private val pending = ConcurrentHashMap.newKeySet<ScheduledTask>()
    private val saves = mutableMapOf<UUID, CompletableFuture<*>>()
    @Volatile private var active = true

    fun atPlayer(player: Player, action: Runnable) {
        if (!active) return
        if (owns(player)) {
            if (active) action.run()
            return
        }
        val finished = AtomicBoolean()
        val handle = AtomicReference<ScheduledTask?>()
        val cleanup = Runnable {
            finished.set(true)
            handle.get()?.let(pending::remove)
        }
        val task = dispatch(player, Runnable {
            cleanup.run()
            if (active) action.run()
        }, cleanup) ?: return
        handle.set(task)
        pending.add(task)
        if (finished.get()) pending.remove(task)
        if (!active) { pending.remove(task); task.cancel() }
    }

    @Synchronized fun save(uuid: UUID, start: () -> CompletableFuture<*>): CompletableFuture<*>? {
        if (!active || uuid in saves || saves.size >= 128) return null
        val future = start()
        saves[uuid] = future
        future.whenComplete { _, _ -> finishSave(uuid, future) }
        return future
    }

    @Synchronized private fun finishSave(uuid: UUID, future: CompletableFuture<*>) {
        saves.remove(uuid, future)
    }

    override fun close() {
        active = false
        pending.forEach { it.cancel() }
        pending.clear()
        synchronized(this) {
            val old = saves.values.toList()
            saves.clear()
            old.forEach { it.cancel(false) }
        }
    }
}

private fun storyTasks(): StorySession? = get<StoryRuntime>(StoryRuntime::class.java).tasks

/** Pays once through the welfare journal; a pending submission never advances quest progress. */
@Entry("jj_story_reward", "通过啾啾福利安全结算一次性任务奖励", "#D9ADDD", "fa6-solid:gift")
class StoryRewardEntry(
    override val id: String = "",
    override val name: String = "",
    override val criteria: List<Criteria> = emptyList(),
    override val modifiers: List<Modifier> = emptyList(),
    override val triggers: List<Ref<TriggerableEntry>> = emptyList(),
    val rewardKey: String = "",
    val label: String = "任务奖励",
    val j: Double = 0.0,
    val items: List<String> = emptyList(),
    val messagePrefix: String = "§d任务手册 §8» §f",
    val serviceUnavailable: String = "§e任务奖励服务尚未就绪，请联系管理员；领取资格保留。",
    val invalidReward: String = "§e任务奖励配置无效，请联系管理员；领取资格保留。",
) : ActionEntry {
    override fun ActionTrigger.execute() {
        disableAutomaticTriggering()
        val tasks = storyTasks() ?: return
        val run = Runnable {
            val service = Bukkit.getServicesManager().load(QuestRewardLedger::class.java)
            if (service == null) {
                player.sendMessage(serviceUnavailable)
                return@Runnable
            }
            val plan = try {
                QuestRewardLedger.Plan(rewardKey, label,
                    WelfareLedger.RewardData(j, 0, 0, 0, 0, 0, 0, items, 0, false))
            } catch (_: IllegalArgumentException) {
                player.sendMessage(invalidReward)
                return@Runnable
            }
            val result = service.claim(player, plan)
            if (result.state() == QuestRewardLedger.State.COMPLETED) triggerManually()
            else player.sendMessage(messagePrefix + result.message())
        }
        tasks.atPlayer(player, run)
    }
}

/** Observes actual durable completion, including a safe resume after reconnecting. */
@Entry("on_jj_story_reward_paid", "当任务奖励真正到账时继续剧情", "#F6D78E", "fa6-solid:check")
class StoryRewardPaidEntry(
    override val id: String = "",
    override val name: String = "",
    override val triggers: List<Ref<TriggerableEntry>> = emptyList(),
    val rewardKey: String = "",
) : EventEntry

@EntryListener(StoryRewardPaidEntry::class)
fun onStoryRewardPaid(event: WelfareSettlementEvent, query: Query<StoryRewardPaidEntry>) {
    if (event.origin != QuestRewardLedger.ORIGIN || event.phase != WelfareSettlementEvent.Phase.COMPLETED) return
    val tasks = storyTasks() ?: return
    val run = Runnable {
        query.findWhere { it.rewardKey == event.prizeKey }.triggerAllFor(event.player, context())
    }
    tasks.atPlayer(event.player, run)
}

/** Exact world matching avoids silently checking coordinates in an unrelated world. */
@Entry("jj_story_near_location", "玩家是否真的站在剧情地点附近", "#C1B0E8", "mdi:map-marker-radius")
class StoryNearLocationFact(
    override val id: String = "",
    override val name: String = "",
    override val comment: String = "",
    override val group: Ref<GroupEntry> = emptyRef(),
    val worldName: String = "",
    val x: Double = 0.0,
    val y: Double = 0.0,
    val z: Double = 0.0,
    val radius: Double = 6.0,
) : ReadableFactEntry {
    override fun readSinglePlayer(player: Player): FactData {
        if (!Bukkit.isOwnedByCurrentRegion(player)) return FactData(0)
        val location = player.location
        return FactData(if (nearLocation(worldName, x, y, z, radius, location.world?.name,
            location.x, location.y, location.z)) 1 else 0)
    }
}

internal fun nearLocation(world: String, x: Double, y: Double, z: Double, radius: Double,
    actualWorld: String?, px: Double, py: Double, pz: Double): Boolean {
    if (world.isBlank() || world != actualWorld || !radius.isFinite() || radius !in 1.0..32.0) return false
    if (listOf(x, y, z, px, py, pz).any { !it.isFinite() }) return false
    return (x - px) * (x - px) + (y - py) * (y - py) + (z - pz) * (z - pz) <= radius * radius
}

/** Grants a permission idempotently; only a successful save advances its configured triggers. */
@Entry("jj_story_unlock_permission", "解锁本章纪念称号权限并保存", "#D9ADDD", "fa6-solid:tag")
class StoryUnlockPermissionEntry(
    override val id: String = "",
    override val name: String = "",
    override val criteria: List<Criteria> = emptyList(),
    override val modifiers: List<Modifier> = emptyList(),
    override val triggers: List<Ref<TriggerableEntry>> = emptyList(),
    val permission: String = "dtag.jiujiu_zero_postman",
    val invalidPermission: String = "§e称号权限配置无效，请联系管理员。",
    val saveFailed: String = "§e纪念称号暂未保存，请稍后在任务手册点击补领；钱和物品不会重复发。",
    val serviceUnavailable: String = "§e称号解锁服务暂不可用，请稍后从任务手册补领。",
) : ActionEntry {
    override fun ActionTrigger.execute() {
        disableAutomaticTriggering()
        val tasks = storyTasks() ?: return
        if (!permission.matches(Regex("dtag\\.[a-z0-9_.-]{1,96}"))) {
            tasks.atPlayer(player, Runnable { player.sendMessage(invalidPermission) })
            return
        }
        val run = Runnable {
            val uuid = player.uniqueId
            try {
                val save = tasks.save(uuid) {
                    LuckPermsProvider.get().userManager.modifyUser(uuid) { user ->
                        user.data().add(Node.builder(permission).value(true).build())
                    }.orTimeout(10, TimeUnit.SECONDS)
                }
                if (save == null) {
                    player.sendMessage(serviceUnavailable)
                    return@Runnable
                }
                save.whenComplete { _, failure ->
                    tasks.atPlayer(player, Runnable {
                        if (failure == null) triggerManually()
                        else player.sendMessage(saveFailed)
                    })
                }
            } catch (_: IllegalStateException) {
                player.sendMessage(serviceUnavailable)
            }
        }
        tasks.atPlayer(player, run)
    }
}
