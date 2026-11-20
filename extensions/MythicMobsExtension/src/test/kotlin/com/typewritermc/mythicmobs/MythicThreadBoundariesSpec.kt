package com.typewritermc.mythicmobs

import com.typewritermc.engine.paper.utils.FoliaScheduler
import com.typewritermc.engine.paper.utils.foliaScheduler
import com.typewritermc.mythicmobs.entries.fact.MobCountFact
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.lumine.mythic.api.adapters.AbstractEntity
import io.lumine.mythic.api.mobs.MythicMob
import io.lumine.mythic.bukkit.MythicBukkit
import io.lumine.mythic.core.mobs.ActiveMob
import io.mockk.*
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import java.util.Optional

class MythicThreadBoundariesSpec : FunSpec({
    afterEach { unmockkAll() }

    test("counting mobs neither resolves live mob types nor changes Mythic's registry") {
        mockkStatic(MythicBukkit::class)
        val mythic = mockk<MythicBukkit>(relaxed = true)
        val type = mockk<MythicMob>()
        val first = mockk<ActiveMob>()
        val second = mockk<ActiveMob>()
        every { MythicBukkit.inst() } returns mythic
        every { mythic.mobManager.getMythicMob("boss") } returns Optional.of(type)
        every { type.internalName } returns "Boss"
        every { first.mobType } returns "Boss"
        every { second.mobType } returns "Other"
        every { first.type } throws IllegalStateException("must not resolve another owner's type")
        every { second.type } throws IllegalStateException("must not resolve another owner's type")
        val registry = mutableListOf(first, second)
        every { mythic.mobManager.activeMobs } returns registry

        MobCountFact(mobName = "boss").readSinglePlayer(mockk<Player>()).value shouldBe 1
        registry.size shouldBe 2
        verify(exactly = 0) { first.type; second.type }
    }

    test("removal waits for the mob owner and ignores a replaced entity handle") {
        mockkStatic("com.typewritermc.engine.paper.utils.PaperCoroutineKt")
        val scheduler = mockk<FoliaScheduler>()
        val entity = mockk<Entity>()
        val abstractEntity = mockk<AbstractEntity>()
        val mob = mockk<ActiveMob>(relaxed = true)
        val callback = slot<Runnable>()
        every { foliaScheduler } returns scheduler
        every { abstractEntity.bukkitEntity } returns entity
        every { mob.cachedEntity } returns abstractEntity
        every { scheduler.atEntity(entity, capture(callback), null, 1L) } returns null

        mob.removeOnOwner()
        verify(exactly = 0) { mob.remove() }
        callback.captured.run()
        verify(exactly = 1) { mob.remove() }

        mob.removeOnOwner()
        every { mob.cachedEntity } returns mockk { every { bukkitEntity } returns mockk<Entity>() }
        callback.captured.run()
        verify(exactly = 1) { mob.remove() }
    }
})
