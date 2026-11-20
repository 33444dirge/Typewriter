package com.typewritermc.roadnetwork

import com.typewritermc.roadnetwork.pathfinding.pathetic.PathSearchBudget
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.*

class PathSearchBudgetSpec : FunSpec({
    test("overload rejects a search before it can allocate a snapshot") {
        val budget = PathSearchBudget(1, 0)
        coroutineScope {
            val entered = CompletableDeferred<Unit>()
            val first = launch(start = CoroutineStart.UNDISPATCHED) {
                budget.run { entered.complete(Unit); awaitCancellation() }
            }
            entered.await()
            var allocated = false
            budget.run { allocated = true; true } shouldBe null
            allocated shouldBe false
            first.cancelAndJoin()
            budget.run { "released" } shouldBe "released"
        }
    }

    test("cancelled waiters return their admission slot before the active search ends") {
        val budget = PathSearchBudget(1, 1)
        coroutineScope {
            val first = launch(start = CoroutineStart.UNDISPATCHED) { budget.run { awaitCancellation() } }
            val waiting = async(start = CoroutineStart.UNDISPATCHED) { budget.run { "queued" } }
            budget.run { "overflow" } shouldBe null
            waiting.cancelAndJoin()
            val replacement = async(start = CoroutineStart.UNDISPATCHED) { budget.run { "replacement" } }
            replacement.isCompleted shouldBe false
            first.cancelAndJoin()
            replacement.await() shouldBe "replacement"
        }
    }

    test("search exceptions and timeouts return both the permit and the admission slot") {
        val budget = PathSearchBudget(1, 0)
        shouldThrow<IllegalStateException> { budget.run<Unit> { error("search failed") } }
        withTimeoutOrNull(20) { budget.run { awaitCancellation() } } shouldBe null
        budget.run { "next" } shouldBe "next"
    }
})
