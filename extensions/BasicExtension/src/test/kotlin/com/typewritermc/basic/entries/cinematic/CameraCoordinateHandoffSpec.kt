package com.typewritermc.basic.entries.cinematic

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

class CameraCoordinateHandoffSpec : FunSpec({
    test("playing translates client positions without changing server coordinates") {
        val handoff = CameraCoordinateHandoff()
        handoff.outgoingY(80.0, 10) shouldBe 580.0
        handoff.incomingY(580.0) shouldBe 80.0
    }

    test("old offset movement cannot reach the server during restoration") {
        val handoff = CameraCoordinateHandoff()
        handoff.beginRestore()
        handoff.incomingY(580.0) shouldBe null
        handoff.outgoingY(80.0, 11) shouldBe 80.0
        handoff.incomingY(580.0) shouldBe null
        handoff.incomingY(80.0) shouldBe null
        handoff.confirm(11)
        handoff.incomingY(80.0) shouldBe 80.0
        handoff.awaitConfirmation() shouldBe true
    }

    test("a playing teleport acknowledgement cannot finish restoration") {
        val handoff = CameraCoordinateHandoff()
        handoff.outgoingY(80.0, 10)
        handoff.confirm(10)
        handoff.beginRestore()
        handoff.confirm(10)
        handoff.incomingY(580.0) shouldBe null
        handoff.outgoingY(80.0, 11)
        handoff.confirm(10)
        handoff.incomingY(580.0) shouldBe null
        handoff.confirm(11)
        handoff.awaitConfirmation() shouldBe true
    }

    test("a replacement recovery teleport needs its own acknowledgement") {
        val handoff = CameraCoordinateHandoff()
        handoff.beginRestore()
        handoff.outgoingY(80.0, 11)
        handoff.outgoingY(81.0, 12)
        handoff.confirm(11)
        handoff.incomingY(581.0) shouldBe null
        handoff.confirm(12)
        handoff.awaitConfirmation() shouldBe true
    }

    test("retiring releases a suspended recovery without touching player state") {
        coroutineScope {
            val handoff = CameraCoordinateHandoff()
            handoff.beginRestore()
            val waiting = async { handoff.awaitConfirmation() }
            handoff.close()
            waiting.await() shouldBe false
            handoff.incomingY(80.0) shouldBe 80.0
            handoff.outgoingY(80.0, 12) shouldBe 80.0
        }
    }

    test("closed handoffs cannot restart or transform a late packet") {
        val handoff = CameraCoordinateHandoff()
        handoff.close()
        handoff.beginRestore()
        handoff.outgoingY(80.0, 12) shouldBe 80.0
        handoff.confirm(12)
        handoff.awaitConfirmation() shouldBe false
    }

    test("repeated teardown does not discard the expected confirmation") {
        val handoff = CameraCoordinateHandoff()
        handoff.beginRestore()
        handoff.outgoingY(80.0, 11)
        handoff.beginRestore()
        handoff.confirm(11)
        handoff.awaitConfirmation() shouldBe true
        handoff.outgoingY(80.0, 12) shouldBe 80.0
        handoff.close()
    }
})
