package com.typewritermc.entity

import com.typewritermc.core.entries.Ref
import com.typewritermc.core.utils.point.Position
import com.typewritermc.engine.paper.TypewriterPaperPlugin
import com.typewritermc.engine.paper.entry.AudienceManager
import com.typewritermc.engine.paper.entry.entity.*
import com.typewritermc.engine.paper.entry.entries.*
import com.typewritermc.engine.paper.plugin
import com.typewritermc.engine.paper.interaction.PlayerSessionManager
import com.typewritermc.engine.paper.utils.FoliaScheduler
import com.typewritermc.engine.paper.utils.foliaScheduler
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.*
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Server
import org.bukkit.World
import org.bukkit.entity.Player
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.util.UUID

private class MembershipActivity(override var currentPosition: PositionProperty) : GenericEntityActivity {
    var disposals = 0
    override fun activate(context: ActivityContext, position: PositionProperty) { currentPosition = position }
    override fun tick(context: ActivityContext) = TickResult.IGNORED
    override fun dispose() { disposals++ }
}

class GroupEntityMembershipSpec : FunSpec({
    lateinit var display: GroupAudienceEntityDisplay
    val players = List(2) { mockk<Player>() }
    val groups = mutableMapOf<UUID, GroupId>()
    val activities = mutableListOf<MembershipActivity>()
    val entities = mutableListOf<FakeEntity>()
    val world = mockk<World>()
    val worldId = UUID.randomUUID()
    val spawn = Position(com.typewritermc.core.utils.point.World(worldId.toString()), 0.0, 64.0, 0.0)

    beforeTest {
        activities.clear()
        entities.clear()
        groups.clear()
        mockkStatic(Bukkit::class)
        mockkStatic("com.typewritermc.engine.paper.TypewriterPaperPluginKt")
        mockkStatic("com.typewritermc.engine.paper.utils.PaperCoroutineKt")
        val server = mockk<Server>(relaxed = true)
        val scheduler = mockk<FoliaScheduler>()
        every { Bukkit.getServer() } returns server
        every { plugin } returns mockk<TypewriterPaperPlugin>(relaxed = true)
        every { foliaScheduler } returns scheduler
        every { scheduler.owns(any<Player>()) } returns true
        every { world.uid } returns worldId
        players.forEach { player ->
            val id = UUID.randomUUID()
            every { player.uniqueId } returns id
            every { player.location } returns Location(world, 0.0, 64.0, 0.0)
            every { Bukkit.isOwnedByCurrentRegion(player) } returns true
            every { server.getPlayer(id) } returns player
            groups[id] = GroupId("old")
        }
        val group = mockk<GroupEntry>()
        every { group.groupId(any()) } answers { groups[firstArg<Player>().uniqueId] }
        val ref = mockk<Ref<EntityInstanceEntry>>()
        every { ref.get() } returns null
        val creator = object : EntityCreator {
            override fun create(player: Player): FakeEntity = mockk<FakeEntity>(relaxed = true).also {
                every { it.identity } returns EntityIdentity(entities.size + 1, UUID.randomUUID())
                every { it.state } returns EntityState()
                entities += it
            }
        }
        val activityCreator = object : ActivityCreator {
            override fun create(context: ActivityContext, currentLocation: PositionProperty) =
                MembershipActivity(currentLocation).also(activities::add)
        }
        startKoin { modules(module {
            single { mockk<AudienceManager>(relaxed = true) }
            single { PlayerSessionManager() }
        }) }
        display = GroupAudienceEntityDisplay(ref, creator, activityCreator, emptyList(), spawn, ConstVar(50.0), group)
    }

    afterTest {
        display.dispose()
        stopKoin()
        unmockkStatic(Bukkit::class)
        unmockkStatic("com.typewritermc.engine.paper.TypewriterPaperPluginKt")
        unmockkStatic("com.typewritermc.engine.paper.utils.PaperCoroutineKt")
    }

    test("changing group replaces the visible entity and releases its old group's activity") {
        val player = players.first()
        display.addPlayer(player)
        activities.size shouldBe 1
        entities.size shouldBe 1
        groups[player.uniqueId] = GroupId("new")

        display.tick()

        activities.size shouldBe 2
        activities.first().disposals shouldBe 1
        activities.last().disposals shouldBe 0
        entities.size shouldBe 2
        verify(exactly = 1) { entities.first().dispose() }
        display.removePlayer(player)
        activities.last().disposals shouldBe 1
        verify(exactly = 1) { entities.last().dispose() }
    }

    test("quitting after a group change still removes the recorded original membership") {
        players.forEach(display::addPlayer)
        activities.size shouldBe 1
        groups[players.first().uniqueId] = GroupId("new")
        display.removePlayer(players.first())
        activities.single().disposals shouldBe 0
        groups[players.last().uniqueId] = GroupId("other")
        display.removePlayer(players.last())
        activities.single().disposals shouldBe 1
        entities.forEach { entity -> verify(exactly = 1) { entity.dispose() } }
    }
})
