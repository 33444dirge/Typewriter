package com.typewritermc.engine.paper.utils

import org.bukkit.Location
import org.bukkit.SoundCategory
import org.bukkit.entity.Player

/** Reads terrain on its region and sends only the sound snapshot back to this viewer. */
fun Player.playStepSoundAt(location: Location, stillValid: () -> Boolean = { true }) {
    val target = location.clone()
    foliaScheduler.atRegion(target, {
        val world = target.world ?: return@atRegion
        if (!world.isChunkLoaded(target.blockX shr 4, target.blockZ shr 4)) return@atRegion
        val sound = target.block.blockData.soundGroup.stepSound
        foliaScheduler.atEntity(this, {
            if (isOnline && world.uid == this.world.uid && stillValid()) {
                playSound(target, sound, SoundCategory.NEUTRAL, 0.4f, 1.0f)
            }
        }, null, 1L)
    }, 1L)
}
