package com.typewritermc.engine.paper.utils

import com.google.gson.Gson
import com.typewritermc.core.utils.point.Vector
import com.typewritermc.engine.paper.logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataType
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import java.util.UUID
import java.util.logging.Level

data class StateRecoveryTicket internal constructor(val token: UUID)

internal data class RecoveryPosition(
    val world: UUID,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float,
    val pitch: Float,
)

internal data class RecoveryEffect(
    val type: String,
    val present: Boolean,
    val duration: Int = 0,
    val amplifier: Int = 0,
    val ambient: Boolean = false,
    val particles: Boolean = false,
    val icon: Boolean = false,
)

internal data class RecoveryRecord(
    val schema: Int,
    val token: UUID,
    val position: RecoveryPosition?,
    val velocity: Vector?,
    val effects: List<RecoveryEffect>,
) {
    fun validate(): RecoveryRecord = apply {
        require(schema == 1) { "Unknown player recovery schema: $schema" }
        requireNotNull(token) { "Missing player recovery token" }
        position?.let {
            requireNotNull(it.world) { "Missing player recovery world" }
            require(listOf(it.x, it.y, it.z, it.yaw.toDouble(), it.pitch.toDouble()).all(Double::isFinite))
        }
        velocity?.let { require(listOf(it.x, it.y, it.z).all(Double::isFinite)) }
        requireNotNull(effects).forEach {
            require(it.type.matches(Regex("[a-z0-9_.-]+:[a-z0-9_/.-]+")))
            require(it.duration >= -1 && it.amplifier >= 0)
        }
    }
}

internal object RecoveryCodec {
    private val gson = Gson()
    fun encode(record: RecoveryRecord): String = gson.toJson(record.validate())
    fun decode(value: String): RecoveryRecord = gson.fromJson(value, RecoveryRecord::class.java).validate()
}

private val recoveryKey = NamespacedKey("typewriter", "cinematic_restore")

private class RecoveryAttempt(val player: Player) {
    val result = CompletableDeferred<Boolean>()
}

private val recoveryAttempts = mutableMapOf<UUID, RecoveryAttempt>()

/** Stores original cinematic state in the same player record that will save the moved position. */
fun Player.rememberStateForRecovery(state: PlayerState): StateRecoveryTicket {
    check(foliaScheduler.owns(this)) { "Player recovery state must be captured on the owner" }
    check(!hasStateRecovery()) { "An interrupted player state must be recovered before starting another cinematic" }
    val location = state.state[GenericPlayerStateProvider.LOCATION] as? Location
    val effects = state.state.mapNotNull { (provider, value) ->
        if (provider !is EffectStateProvider) {
            require(provider == GenericPlayerStateProvider.LOCATION || provider == GenericPlayerStateProvider.VELOCITY) {
                "Unsupported persistent player state: $provider"
            }
            return@mapNotNull null
        }
        val effect = value as? PotionEffect
        RecoveryEffect(
            provider.effect.key.toString(), effect != null, effect?.duration ?: 0, effect?.amplifier ?: 0,
            effect?.isAmbient ?: false, effect?.hasParticles() ?: false, effect?.hasIcon() ?: false,
        )
    }
    val record = RecoveryRecord(
        1, UUID.randomUUID(), location?.let {
            RecoveryPosition(requireNotNull(it.world).uid, it.x, it.y, it.z, it.yaw, it.pitch)
        }, state.state[GenericPlayerStateProvider.VELOCITY] as? Vector, effects,
    )
    persistentDataContainer.set(recoveryKey, PersistentDataType.STRING, RecoveryCodec.encode(record))
    return StateRecoveryTicket(record.token)
}

fun Player.hasStateRecovery(): Boolean = persistentDataContainer.has(recoveryKey)

/** Clears only the snapshot belonging to the completed camera action. */
fun Player.completeStateRecovery(ticket: StateRecoveryTicket) {
    check(foliaScheduler.owns(this)) { "Player recovery state must be completed on the owner" }
    val stored = persistentDataContainer.get(recoveryKey, PersistentDataType.STRING) ?: return
    if (RecoveryCodec.decode(stored).token == ticket.token) persistentDataContainer.remove(recoveryKey)
}

/** Recovers a saved interruption before a replacement Typewriter session can change player state. */
suspend fun Player.recoverInterruptedState(): Boolean {
    check(foliaScheduler.owns(this)) { "Player recovery must start on the owner" }
    if (!hasStateRecovery()) return true
    val (attempt, created) = synchronized(recoveryAttempts) {
        val current = recoveryAttempts[uniqueId]
        if (current?.player === this) current to false
        else RecoveryAttempt(this).also { recoveryAttempts[uniqueId] = it } to true
    }
    if (!created) return attempt.result.await()
    var restored = false
    try {
        val stored = persistentDataContainer.get(recoveryKey, PersistentDataType.STRING)
            ?: error("Player recovery marker has an unsupported data type")
        val record = RecoveryCodec.decode(stored)
        val values = mutableMapOf<PlayerStateProvider, Any>()
        var worldAvailable = true
        record.position?.let {
            val world = Bukkit.getWorld(it.world)
            worldAvailable = world != null
            if (world != null) {
                values[GenericPlayerStateProvider.LOCATION] = Location(world, it.x, it.y, it.z, it.yaw, it.pitch)
            }
        }
        record.velocity?.let { values[GenericPlayerStateProvider.VELOCITY] = it }
        record.effects.forEach {
            val key = requireNotNull(NamespacedKey.fromString(it.type))
            val type = PotionEffectType.getByKey(key) ?: error("Unknown player recovery effect ${it.type}")
            values[EffectStateProvider(type)] = if (it.present) {
                PotionEffect(type, it.duration, it.amplifier, it.ambient, it.particles, it.icon)
            } else false
        }
        val moved = restoreAsync(PlayerState(values)) && worldAvailable
        if (moved) {
            completeStateRecovery(StateRecoveryTicket(record.token))
            restored = true
        }
        else logger.warning("Player recovery teleport was rejected for $uniqueId; its saved marker is retained")
        return restored
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        logger.log(Level.SEVERE, "Failed to recover interrupted Typewriter state for $uniqueId; marker retained", error)
        return false
    } finally {
        attempt.result.complete(restored)
        synchronized(recoveryAttempts) { recoveryAttempts.remove(uniqueId, attempt) }
    }
}
