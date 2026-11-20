package city.jiujiu.typewriter

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class NearLocationSpec : StringSpec({
    "a wrong or missing world never falls back to the first world" {
        nearLocation("worlds_lobby", 0.0, 80.0, 0.0, 6.0, "world", 0.0, 80.0, 0.0) shouldBe false
        nearLocation("worlds_lobby", 0.0, 80.0, 0.0, 6.0, null, 0.0, 80.0, 0.0) shouldBe false
    }
    "vertical separation and the inclusive radius matter" {
        nearLocation("w", 0.0, 80.0, 0.0, 6.0, "w", 0.0, 86.0, 0.0) shouldBe true
        nearLocation("w", 0.0, 80.0, 0.0, 6.0, "w", 0.0, 86.01, 0.0) shouldBe false
    }
    "invalid numeric parameters cannot complete a location condition" {
        nearLocation("w", Double.NaN, 80.0, 0.0, 6.0, "w", 0.0, 80.0, 0.0) shouldBe false
        nearLocation("w", 0.0, 80.0, 0.0, Double.POSITIVE_INFINITY, "w", 0.0, 80.0, 0.0) shouldBe false
        nearLocation("w", 0.0, 80.0, 0.0, 1000.0, "w", 0.0, 80.0, 0.0) shouldBe false
    }
})
