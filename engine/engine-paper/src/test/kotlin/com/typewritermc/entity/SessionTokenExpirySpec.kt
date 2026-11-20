package com.typewritermc.entity

import com.typewritermc.engine.paper.ui.SessionTokenRegistry
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.time.Duration
import java.util.UUID

class SessionTokenExpirySpec : FunSpec({
    test("concurrent links reuse the same player or console token") {
        val tokens = SessionTokenRegistry()
        for (player in listOf(UUID.randomUUID(), null)) {
            val ids = coroutineScope {
                List(200) { async(Dispatchers.Default) { tokens.generate(player) } }.awaitAll()
            }
            ids.toSet().size shouldBe 1
        }
    }

    test("idle links expire while used links renew their idle lifetime") {
        var now = 0L
        val tokens = SessionTokenRegistry(4, Duration.ofNanos(10)) { now }
        val activePlayer = UUID.randomUUID()
        val idle = tokens.generate(UUID.randomUUID())
        val active = tokens.generate(activePlayer)
        now = 9
        tokens.get(active)?.playerId shouldBe activePlayer
        now = 10
        tokens.get(idle) shouldBe null
        tokens.generate(activePlayer) shouldBe active
        now = 20
        tokens.get(active) shouldBe null
        (tokens.generate(activePlayer) != active) shouldBe true
    }

    test("capacity evicts the least recently used link and its player index") {
        val tokens = SessionTokenRegistry(2)
        val firstPlayer = UUID.randomUUID()
        val first = tokens.generate(firstPlayer)
        val second = tokens.generate(UUID.randomUUID())
        tokens.get(first)
        tokens.generate(UUID.randomUUID())
        tokens.get(second) shouldBe null
        tokens.generate(firstPlayer) shouldBe first
    }

    test("stale connection callbacks cannot repopulate a closed registry") {
        val tokens = SessionTokenRegistry()
        val player = UUID.randomUUID()
        val token = tokens.generate(player)
        tokens.close()
        tokens.connected(token, UUID.randomUUID())
        tokens.get(token) shouldBe null
        shouldThrow<IllegalStateException> { tokens.generate(player) }
    }
})
