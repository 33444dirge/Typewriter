package com.typewritermc.mythicmobs

import com.typewritermc.engine.paper.utils.foliaScheduler
import io.lumine.mythic.core.mobs.ActiveMob

/** Uses the cached handle; resolving ActiveMob.entity or type can mutate Mythic's registry. */
internal fun ActiveMob.removeOnOwner(despawn: Boolean = false) {
    val entity = cachedEntity?.bukkitEntity ?: return
    foliaScheduler.atEntity(entity, {
        if (cachedEntity?.bukkitEntity !== entity) return@atEntity
        if (despawn) despawn() else remove()
    }, null, 1L)
}
