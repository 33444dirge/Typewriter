package com.typewritermc.basic.entries.cinematic

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerCamera
import com.typewritermc.core.books.pages.Colors
import com.typewritermc.core.extension.annotations.Entry
import com.typewritermc.core.extension.annotations.Help
import com.typewritermc.core.extension.annotations.Segments
import com.typewritermc.core.utils.point.Position
import com.typewritermc.core.utils.point.World
import com.typewritermc.engine.paper.entry.Criteria
import com.typewritermc.engine.paper.entry.entries.CinematicAction
import com.typewritermc.engine.paper.entry.entries.ConstVar
import com.typewritermc.engine.paper.entry.entries.PrimaryCinematicEntry
import com.typewritermc.engine.paper.entry.entries.RetirableCinematicAction
import com.typewritermc.engine.paper.entry.entries.Segment
import com.typewritermc.engine.paper.entry.entries.Var
import com.typewritermc.engine.paper.entry.entries.activeSegmentAt
import com.typewritermc.engine.paper.entry.entries.canFinishAt
import com.typewritermc.engine.paper.extensions.packetevents.meta
import com.typewritermc.engine.paper.interaction.InterceptionBundle
import com.typewritermc.engine.paper.logger
import com.typewritermc.engine.paper.plugin
import com.typewritermc.engine.paper.utils.asMini
import com.typewritermc.engine.paper.utils.entityDispatcher
import com.typewritermc.engine.paper.utils.isFloodgate
import com.typewritermc.engine.paper.utils.toPacketLocation
import kotlinx.coroutines.withContext
import lirand.api.extensions.events.SimpleListener
import lirand.api.extensions.events.listen
import lirand.api.extensions.events.unregister
import me.tofaa.entitylib.meta.display.TextDisplayMeta
import me.tofaa.entitylib.wrapper.WrapperEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventPriority
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.server.PluginDisableEvent
import java.util.concurrent.atomic.AtomicBoolean

/** A nearby Java-client camera shot that never teleports or changes the player's saved state. */
@Entry("stationary_camera_cinematic", "原地观看附近地点，人物不传送", Colors.CYAN, "fa6-solid:video")
class StationaryCameraCinematicEntry(
    override val id: String = "",
    override val name: String = "",
    override val criteria: List<Criteria> = emptyList(),
    @Segments(icon = "fa6-solid:video")
    val segments: List<StationaryCameraSegment> = emptyList(),
    @Help("可选的精确世界名校验；填写后可防止世界拼错时旧序列化器回落到默认世界。")
    val worldName: String = "",
    @Help("镜头、目标离玩家的最大距离，1至256格；仍须位于已发送区块内。")
    val maxDistance: Double = 96.0,
    @Help("无法观看时显示的MiniMessage提示，可设为空。")
    val unavailableMessage: String = "<yellow>请在白给姐附近站稳，稍等区块加载后再试一次。</yellow>",
) : PrimaryCinematicEntry {
    override fun create(player: Player): CinematicAction = StationaryCameraCinematicAction(player, this)
}

data class StationaryCameraSegment(
    override val startFrame: Int = 0,
    override val endFrame: Int = 140,
    val start: Var<Position> = ConstVar(Position.ORIGIN),
    val end: Var<Position> = ConstVar(Position.ORIGIN),
    val lookAt: Var<Position> = ConstVar(Position.ORIGIN),
    @Help("移动所用帧数，20帧约1秒；剩余时间在终点停留。")
    val moveFrames: Int = 40,
    @Help("起终点使用脚下坐标时加上的眼高，默认1.6格。")
    val eyeOffset: Double = 1.6,
    @Help("目标使用脚下坐标时加上的头部高度，默认1.6格。")
    val targetOffset: Double = 1.6,
    @Help("开启后按起终点的yaw/pitch平滑转向；关闭时始终看向lookAt。北180、南0、西90、东-90。")
    val usePointRotation: Boolean = false,
) : Segment

internal class StationaryCameraCinematicAction(
    private val player: Player,
    private val entry: StationaryCameraCinematicEntry,
) : CinematicAction, RetirableCinematicAction {
    private val interrupted = AtomicBoolean(false)
    private val resources = StationaryCameraResources { logger.warning("Stationary camera cleanup: ${it.message}") }
    @Volatile
    private var ticked = false
    private var shot: StationaryCameraResources? = null
    private var activeSegment: StationaryCameraSegment? = null
    private var entity: WrapperEntity? = null
    private var routes: Map<StationaryCameraSegment, StationaryCameraRoute> = emptyMap()
    private var requiredChunks: Set<Long> = emptySet()
    private var origin: Position? = null

    override suspend fun setup() = withContext(player.entityDispatcher) {
        try {
            resources.use {
                remember {
                    shot?.close()
                    shot = null
                    entity = null
                    activeSegment = null
                    routes = emptyMap()
                    requiredChunks = emptySet()
                    origin = null
                }
                require(!player.isFloodgate) { "Stationary camera requires a Java client" }
                require(player.isOnline && !player.isDead && !player.isInsideVehicle && !player.isGliding) {
                    "Player is not ready for a stationary shot"
                }
                val location = player.location
                val world = location.world!!
                require(entry.worldName.isBlank() || world.name == entry.worldName) { "Camera world name does not match" }
                val key = World(world.uid.toString())
                origin = Position(key, location.x, location.y, location.z)
                val aliases = setOf(world.name, world.uid.toString())
                require(entry.segments.size in 1..32) { "Camera needs 1..32 segments" }
                require(entry.segments.all { it.startFrame >= 0 && it.endFrame in 1..1200 }) {
                    "Camera must finish within 60 seconds"
                }
                routes = entry.segments.associateWith { segment ->
                    fun resolve(value: Var<Position>, offset: Double): Position {
                        require(offset.isFinite() && offset in -16.0..16.0) { "Invalid camera height offset" }
                        val point = value.get(player)
                        require(point.world.identifier in aliases) { "Camera must stay in the player's world" }
                        val resolved = point.copy(world = key, y = point.y + offset)
                        require(resolved.y >= world.minHeight && resolved.y < world.maxHeight) { "Invalid camera Y" }
                        return resolved
                    }
                    StationaryCameraRoute(
                        resolve(segment.start, segment.eyeOffset), resolve(segment.end, segment.eyeOffset),
                        resolve(segment.lookAt, segment.targetOffset), segment.moveFrames,
                        segment.endFrame - segment.startFrame,
                        segment.usePointRotation,
                    ).also { require(it.withinRange(origin!!, entry.maxDistance)) { "Camera is too far from the player" } }
                }
                requiredChunks = routes.values.flatMap { it.requiredChunks() }.toSet()
                require(requiredChunks.all { player.isChunkSent(it) }) { "Camera chunks have not been sent" }
            }
        } catch (error: Exception) {
            fail(error)
        }
    }

    override suspend fun tick(frame: Int) = withContext(player.entityDispatcher) {
        try {
            resources.use {
                if (interrupted.get() || !ownerUnmoved() || requiredChunks.any { !player.isChunkSent(it) }) {
                    close()
                    return@use
                }
                val segment = entry.segments activeSegmentAt frame
                if (segment != activeSegment) {
                    shot?.close()
                    shot = null
                    entity = null
                    activeSegment = segment
                    if (segment != null) startShot(routes.getValue(segment), frame - segment.startFrame)
                }
                if (segment != null) entity?.teleport(routes.getValue(segment).position(frame - segment.startFrame).toPacketLocation())
            }
        } catch (error: Exception) {
            fail(error)
        } finally {
            ticked = true
        }
    }

    private fun ownerUnmoved(): Boolean {
        val initial = origin ?: return false
        if (!player.isOnline || player.isDead) return false
        val now = player.location
        if (now.world?.uid.toString() != initial.world.identifier) return false
        val dx = now.x - initial.x
        val dy = now.y - initial.y
        val dz = now.z - initial.z
        return dx * dx + dy * dy + dz * dz < 0.01
    }

    private fun startShot(route: StationaryCameraRoute, frame: Int) {
        val lease = StationaryCameraResources { logger.warning("Stationary camera shot cleanup: ${it.message}") }
        shot = lease
        val viewerId = player.uniqueId
        val bodyId = player.entityId
        val user = requireNotNull(PacketEvents.getAPI().playerManager.getUser(player)) { "Missing packet connection" }
        val camera = WrapperEntity(EntityTypes.TEXT_DISPLAY).meta<TextDisplayMeta> {
            positionRotationInterpolationDuration = 1
        }
        entity = camera
        lease.remember { camera.remove() }
        lease.remember { camera.despawn() }
        camera.spawn(route.position(frame).toPacketLocation())
        camera.addViewer(viewerId)

        val listener = SimpleListener()
        lease.remember { listener.unregister() }
        plugin.listen<EntityDamageEvent>(priority = EventPriority.MONITOR, ignoreCancelled = true, listener = listener) {
            if (it.entity.uniqueId == viewerId) interrupted.set(true)
        }
        plugin.listen<PlayerQuitEvent>(listener = listener) {
            if (it.player.uniqueId == viewerId) retire()
        }
        plugin.listen<PluginDisableEvent>(listener = listener) {
            if (it.plugin === plugin) retire()
        }

        val interception = InterceptionBundle(viewerId)
        lease.remember { interception.cancel() }
        with(interception) {
            !PacketType.Play.Client.PLAYER_POSITION
            !PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION
            !PacketType.Play.Client.PLAYER_ROTATION
            !PacketType.Play.Client.PLAYER_FLYING
            !PacketType.Play.Client.PLAYER_INPUT
            !PacketType.Play.Client.INTERACT_ENTITY
            !PacketType.Play.Client.PLAYER_DIGGING
            !PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT
            !PacketType.Play.Client.USE_ITEM
            PacketType.Play.Server.PLAYER_POSITION_AND_LOOK { interrupted.set(true) }
        }
        lease.remember { user.sendPacket(WrapperPlayServerCamera(bodyId)) }
        user.sendPacket(WrapperPlayServerCamera(camera.entityId))
    }

    private fun fail(error: Exception) {
        resources.close()
        logger.warning("Stationary camera '${entry.id}' stopped: ${error.message}")
        if (player.isOnline && entry.unavailableMessage.isNotBlank()) player.sendMessage(entry.unavailableMessage.asMini())
    }

    override suspend fun teardown() = resources.close()
    override fun retire() = resources.close()
    override fun canFinish(frame: Int): Boolean =
        if (resources.closed) ticked else entry.segments canFinishAt frame
}
