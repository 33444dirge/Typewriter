package com.typewritermc.worldguard

import com.sk89q.worldguard.protection.regions.ProtectedRegion
import com.typewritermc.worldguard.events.RegionsEnterEvent
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.entity.Player
import java.util.UUID

class RegionMembershipSpec : FunSpec({
    fun region(id: String): ProtectedRegion = mockk<ProtectedRegion>().also {
        every { it.id } returns id
    }

    test("initial membership is silent and later crossings use copied result collections") {
        val membership = RegionMembership()
        val world = UUID.randomUUID()
        val town = region("town")
        val field = region("field")
        val result = mutableSetOf(town)
        membership.update(world, result) shouldBe RegionChanges(emptySet(), emptySet())
        result.clear()
        membership.update(world, setOf(field)) shouldBe RegionChanges(setOf(field), setOf(town))
        membership.update(world, setOf(field)) shouldBe RegionChanges(emptySet(), emptySet())
    }

    test("switching worlds exits and enters regions even when both worlds use the same name") {
        val membership = RegionMembership()
        val first = region("town")
        val second = region("town")
        membership.update(UUID.randomUUID(), setOf(first))
        membership.update(UUID.randomUUID(), setOf(second)) shouldBe RegionChanges(setOf(second), setOf(first))
    }

    test("region events keep stable membership after the source set and region objects change") {
        val region = region("town")
        val source = mutableSetOf(region)
        val event = RegionsEnterEvent(mockk<Player>(), source)
        source.clear()
        every { region.id } returns "renamed"
        ("town" in event) shouldBe true
        ("renamed" in event) shouldBe false
        event.regions.size shouldBe 1
    }
})
