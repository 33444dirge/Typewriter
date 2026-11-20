package com.typewritermc.basic.entries.audience

import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange
import com.typewritermc.core.books.pages.Colors
import com.typewritermc.core.extension.annotations.*
import com.typewritermc.core.utils.point.Position
import com.typewritermc.engine.paper.entry.entries.*
import com.typewritermc.engine.paper.extensions.packetevents.sendPacketTo
import com.typewritermc.engine.paper.interaction.interactionContext
import com.typewritermc.engine.paper.utils.*
import io.github.retrooper.packetevents.util.SpigotConversionUtil
import org.bukkit.Material
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Entry("set_fake_block_audience", "Set a fake block for an audience", Colors.GREEN, "mingcute:cube-3d-fill")
/**
 * The `SetFakeBlockAudienceEntry` is an audience entry that would set a fake block.
 *
 * ## How could this be used?
 * This could be used to create illusions or special effects for players.
 */
class SetFakeBlockAudienceEntry(
    override val id: String = "",
    override val name: String = "",
    @Help("The location where the fake block will be set")
    val location: Var<Position> = ConstVar(Position.ORIGIN),
    @MaterialProperties(MaterialProperty.BLOCK)
    @Help("The block to fake.")
    val block: Var<Material> = ConstVar(Material.AIR),
) : AudienceEntry {
    override suspend fun display() = SetFakeBlockDisplay(location, block)
}

private data class PlayerBlock(
    val position: Position,
    val material: Material
)

class SetFakeBlockDisplay(
    private val position: Var<Position>,
    private val block: Var<Material>,
) : AudienceDisplay(), TickableDisplay {
    private val blocks = ConcurrentHashMap<UUID, PlayerBlock>()

    override fun tick() {
        forEachPlayer { player ->
            val playerId = player.uniqueId
            val state = blocks[playerId] ?: return@forEachPlayer
            val context = player.interactionContext
            val currentPosition = position.get(player, context)
            val currentMaterial = block.get(player, context)
            if (currentPosition != state.position || currentMaterial != state.material) {
                blocks[playerId] = PlayerBlock(currentPosition, currentMaterial)
                if (currentPosition != state.position) restoreBlock(player, state.position)
            }
            sendBlockChange(player, currentPosition, currentMaterial)
        }
    }

    override fun onPlayerAdd(player: Player) {
        val context = player.interactionContext
        val position = position.get(player, context)
        val material = block.get(player, context)
        blocks[player.uniqueId] = PlayerBlock(position, material)
        sendBlockChange(player, position, material)
    }

    override fun onPlayerRemove(player: Player) {
        blocks.remove(player.uniqueId)?.let { restoreBlock(player, it.position) }
    }

    private fun restoreBlock(player: Player, position: Position) {
        val location = position.toBukkitLocation()
        foliaScheduler.atRegion(location, {
            val data = location.block.blockData.clone()
            foliaScheduler.atEntity(player, {
                if (blocks[player.uniqueId]?.position != position && player.world.uid == location.world?.uid) {
                    player.sendBlockChange(location, data)
                }
            }, null, 1L)
        }, 1L)
    }

    private fun sendBlockChange(player: Player, position: Position, material: Material) {
        if (player.world.uid != position.toBukkitLocation().world?.uid) return
        WrapperPlayServerBlockChange(
            position.toPacketVector3i(),
            SpigotConversionUtil.fromBukkitBlockData(material.createBlockData())
        ).sendPacketTo(player)
    }
}
