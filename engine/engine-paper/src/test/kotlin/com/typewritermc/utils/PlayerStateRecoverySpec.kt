package com.typewritermc.utils

import com.typewritermc.core.utils.point.Vector
import com.typewritermc.engine.paper.utils.*
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class PlayerStateRecoverySpec : FunSpec({
    val record = RecoveryRecord(
        1, UUID.randomUUID(), RecoveryPosition(UUID.randomUUID(), 10000.5, -60.0, 0.5, 90f, -10f),
        Vector(0.1, 0.0, -0.1), listOf(RecoveryEffect("minecraft:invisibility", true, -1, 2, false, true, true)),
    )

    test("saved recovery preserves world identity, original effects, velocity and completion token") {
        RecoveryCodec.decode(RecoveryCodec.encode(record)) shouldBe record
    }

    test("an unknown format cannot be mistaken for an empty successful restoration") {
        shouldThrow<IllegalArgumentException> {
            RecoveryCodec.decode(RecoveryCodec.encode(record).replace("\"schema\":1", "\"schema\":2"))
        }
        shouldThrow<IllegalArgumentException> { RecoveryCodec.decode("{\"schema\":1,\"effects\":[]}") }
    }

    test("invalid destinations and potion durations are rejected before persisting them") {
        shouldThrow<IllegalArgumentException> {
            RecoveryCodec.encode(record.copy(position = record.position!!.copy(x = Double.NaN)))
        }
        shouldThrow<IllegalArgumentException> {
            RecoveryCodec.encode(record.copy(effects = listOf(RecoveryEffect("minecraft:invisibility", true, -2))))
        }
    }
})
