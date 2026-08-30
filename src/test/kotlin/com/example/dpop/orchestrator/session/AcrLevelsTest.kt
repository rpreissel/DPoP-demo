package com.example.dpop.orchestrator.session

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * Pure unit test of [AcrLevels] - the ordering every security decision in this app is built on
 * (isSatisfied, canAccountReach, the MFA bump). A bug here would silently corrupt all of them.
 */
class AcrLevelsTest : BehaviorSpec({

    given("rank") {
        then("known levels rank in the documented order") {
            AcrLevels.rank("none") shouldBe 0
            AcrLevels.rank("loa1") shouldBe 1
            AcrLevels.rank("loa2") shouldBe 2
            AcrLevels.rank("loa3") shouldBe 3
        }

        then("an unknown level ranks as if it were 'none' - never negative, never highest") {
            AcrLevels.rank("bogus") shouldBe 0
        }

        then("null ranks as 'none'") {
            AcrLevels.rank(null) shouldBe 0
        }
    }

    given("levelAt, the inverse of rank") {
        then("round-trips every known level") {
            listOf("none", "loa1", "loa2", "loa3").forEach {
                AcrLevels.levelAt(AcrLevels.rank(it)) shouldBe it
            }
        }

        then("an out-of-range rank falls back to 'none' rather than throwing") {
            AcrLevels.levelAt(99) shouldBe "none"
            AcrLevels.levelAt(-1) shouldBe "none"
        }
    }

    given("max/min") {
        then("max picks the higher-ranked level") {
            AcrLevels.max("loa1", "loa2") shouldBe "loa2"
            AcrLevels.max("loa2", "loa1") shouldBe "loa2"
        }

        then("max treats a null side as absent, not as the winner") {
            AcrLevels.max(null, "loa2") shouldBe "loa2"
            AcrLevels.max("loa2", null) shouldBe "loa2"
            AcrLevels.max(null, null) shouldBe "none"
        }

        then("min picks the lower-ranked level") {
            AcrLevels.min("loa1", "loa2") shouldBe "loa1"
        }

        then("min treats either side being null as 'none' - a missing value never counts as satisfied") {
            AcrLevels.min(null, "loa2") shouldBe "none"
            AcrLevels.min("loa2", null) shouldBe "none"
        }
    }

    given("bump") {
        then("moves up by the given number of tiers") {
            AcrLevels.bump("loa1", steps = 1) shouldBe "loa2"
            AcrLevels.bump("loa1", steps = 2) shouldBe "loa3"
        }

        then("is capped at the highest known level, never overflows past it") {
            AcrLevels.bump("loa3", steps = 1) shouldBe "loa3"
            AcrLevels.bump("loa2", steps = 10) shouldBe "loa3"
        }
    }
})
