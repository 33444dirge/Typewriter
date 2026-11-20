package com.typewritermc.facts

import com.typewritermc.core.entries.Entry
import com.typewritermc.core.entries.Library
import com.typewritermc.engine.paper.entry.entries.ExpirableFactEntry
import com.typewritermc.engine.paper.entry.entries.GroupId
import com.typewritermc.engine.paper.entry.entries.PersistableFactEntry
import com.typewritermc.engine.paper.facts.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.*
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import kotlin.reflect.KClass

private interface TimedPersistableFact : ExpirableFactEntry, PersistableFactEntry

class FactMaintenanceSpec : FunSpec({
    val storage = mockk<FactStorage>(relaxed = true)
    val library = Library()
    fun catalogue(entries: Map<KClass<*>, List<Entry>>) {
        Library::class.java.getDeclaredField("entriesByType").apply { isAccessible = true }.set(library, entries)
    }
    fun sweep(database: FactDatabase) {
        FactDatabase::class.java.getDeclaredMethod("removeExpiredFacts").apply { isAccessible = true }.invoke(database)
    }
    beforeTest {
        clearMocks(storage)
        catalogue(emptyMap())
        startKoin { modules(module { single { storage }; single { library } }) }
    }
    afterTest { stopKoin() }

    test("maintenance never discards permanent or unknown historical progress") {
        val database = FactDatabase()
        val id = FactId("permanent", GroupId("offline-player"))
        database[id] = FactData(17)
        sweep(database)
        database[id]?.value shouldBe 17
    }

    test("expiry cannot erase a concurrently refreshed fact") {
        val database = FactDatabase()
        val id = FactId("timed", GroupId("party"))
        val expired = FactData(1)
        val refreshed = FactData(2)
        val entry = mockk<ExpirableFactEntry>()
        every { entry.id } returns "timed"
        every { entry.hasExpired(id, expired) } answers { database[id] = refreshed; true }
        catalogue(mapOf(ExpirableFactEntry::class to listOf(entry)))
        database[id] = expired
        sweep(database)
        database[id] shouldBe refreshed
    }

    test("single-pass persistence keeps offline groups and filters only declared expired facts") {
        val database = FactDatabase()
        val permanent = mockk<PersistableFactEntry>()
        every { permanent.id } returns "permanent"
        every { permanent.canPersist(any(), any()) } returns true
        val timed = mockk<TimedPersistableFact>()
        every { timed.id } returns "timed"
        every { timed.canPersist(any(), any()) } returns true
        every { timed.hasExpired(any(), any()) } returns true
        catalogue(mapOf(PersistableFactEntry::class to listOf(permanent, timed)))
        val first = FactId("permanent", GroupId("offline-a")) to FactData(5)
        val second = FactId("permanent", GroupId("offline-b")) to FactData(9)
        database[first.first] = first.second
        database[second.first] = second.second
        database[FactId("timed", GroupId("expired"))] = FactData(10)
        val saved = slot<Collection<Pair<FactId, FactData>>>()
        coEvery { storage.storeFacts(capture(saved)) } just Runs
        database.shutdown()
        saved.captured.toMap() shouldBe mapOf(first, second)
        coVerify(exactly = 1) { storage.storeFacts(any()) }
    }
})
