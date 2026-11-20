package com.typewritermc.engine.paper.content

import com.github.retrooper.packetevents.protocol.item.ItemStack as PacketItem
import io.github.retrooper.packetevents.util.SpigotReflectionUtil
import org.bukkit.inventory.ItemStack

internal fun inventoryPacketItem(item: ItemStack): PacketItem = SpigotReflectionUtil.decodeBukkitItemStack(item)
