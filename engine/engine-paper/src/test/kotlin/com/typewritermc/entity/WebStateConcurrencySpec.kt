package com.typewritermc.entity

import com.google.gson.Gson
import com.typewritermc.engine.paper.ui.CommunicationHandler
import com.typewritermc.engine.paper.ui.Writer
import com.typewritermc.engine.paper.ui.Writers
import com.typewritermc.loader.ExtensionLoader
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.*
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module

class WebStateConcurrencySpec : FunSpec({
    beforeTest {
        val communication = mockk<CommunicationHandler>()
        every { communication.server } returns null
        startKoin { modules(module {
            single { communication }
            single { mockk<ExtensionLoader>() }
        }) }
    }
    afterTest { stopKoin() }

    test("parallel writers retain every connection and every edit") {
        val writers = Writers()
        coroutineScope {
            repeat(200) { id -> launch(Dispatchers.Default) { writers.addWriter(id.toString(), null) } }
        }
        coroutineScope {
            repeat(200) { id ->
                launch(Dispatchers.Default) { writers.updateWriter(id.toString(), "{\"pageId\":\"page-$id\"}") }
            }
        }
        val saved = Gson().fromJson(writers.serialize(), Array<Writer>::class.java)
        saved.size shouldBe 200
        saved.all { it.pageId == "page-${it.id}" } shouldBe true
    }

    test("callbacks queued before shutdown cannot repopulate disposed writers") {
        val writers = Writers()
        writers.addWriter("previous", null)
        val released = CompletableDeferred<Unit>()
        coroutineScope {
            repeat(50) { id -> launch(Dispatchers.Default) {
                released.await()
                writers.addWriter(id.toString(), null)
                writers.updateWriter(id.toString(), "{\"pageId\":\"stale\"}")
            } }
            writers.dispose()
            released.complete(Unit)
        }
        writers.serialize() shouldBe "[]"
    }

})
