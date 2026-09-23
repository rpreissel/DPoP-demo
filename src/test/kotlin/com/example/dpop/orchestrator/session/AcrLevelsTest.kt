package com.example.dpop.orchestrator.session

import com.example.dpop.tool_spi.AcrLevel
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import com.example.dpop.orchestrator.kernel.AcrLevels

/**
 * Unit test of [AcrLevels], the orchestrator-side REST of the ACR taxonomy (which itself lives
 * in tool_spi as [AcrLevel] and is tested there): the session policy that stayed here
 * ([AcrLevels.DEFAULT_REQUIRED_ACR], [AcrLevels.HIGHEST], [AcrLevels.bump]) plus the String edge
 * mirrors that keep the JPA/Wire boundaries (ChannelSession.acrFloor,
 * AuthenticationMethod.enrolledUnderAcr, the OpenAPI DTOs) working without leaking the typed
 * level across them.
 */
class AcrLevelsTest : BehaviorSpec({

    given("the String edge mirrors (JPA columns, Wire DTOs)") {
        then("rank strings like the typed core does, unknown/null ranking as 'none'") {
            AcrLevels.rank("none") shouldBe 0
            AcrLevels.rank("loa1") shouldBe 1
            AcrLevels.rank("loa2") shouldBe 2
            AcrLevels.rank("loa3") shouldBe 3
            AcrLevels.rank("bogus") shouldBe 0
            AcrLevels.rank(null as String?) shouldBe 0
        }

        then("levelAt round-trips and falls back to 'none' out of range") {
            listOf("none", "loa1", "loa2", "loa3").forEach {
                AcrLevels.levelAt(AcrLevels.rank(it)) shouldBe it
            }
            AcrLevels.levelAt(99) shouldBe "none"
            AcrLevels.levelAt(-1) shouldBe "none"
        }

        then("max picks the higher-ranked string, a null side as absent, not as the winner") {
            AcrLevels.max("loa1", "loa2") shouldBe "loa2"
            AcrLevels.max("loa2", "loa1") shouldBe "loa2"
            AcrLevels.max(null, "loa2") shouldBe "loa2"
            AcrLevels.max("loa2", null) shouldBe "loa2"
            AcrLevels.max(null as String?, null as String?) shouldBe "none"
        }

        then("min picks the lower-ranked string, either side being null meaning 'none' - a missing value never counts as satisfied") {
            AcrLevels.min("loa1", "loa2") shouldBe "loa1"
            AcrLevels.min(null, "loa2") shouldBe "none"
            AcrLevels.min("loa2", null) shouldBe "none"
        }

        then("an unknown string is damped to 'none' instead of passing through - an edge reader must never invent a level") {
            AcrLevels.max("bogus", "loa1") shouldBe "loa1"
            AcrLevels.min("bogus", "loa1") shouldBe "none"
        }
    }

    given("the session policy constants") {
        then("the default required ACR a session floor starts at is loa1") {
            AcrLevels.DEFAULT_REQUIRED_ACR.value shouldBe "loa1"
        }

        then("HIGHEST is the top of the known taxonomy, never a made-up ceiling") {
            AcrLevels.HIGHEST.value shouldBe AcrLevel.KNOWN.last()
        }
    }

    given("bump, the MFA rule's one-step-up helper") {
        then("moves up by the given number of tiers") {
            AcrLevels.bump(AcrLevel.LOA1, steps = 1) shouldBe AcrLevel.LOA2
            AcrLevels.bump(AcrLevel.LOA1, steps = 2) shouldBe AcrLevel.LOA3
        }

        then("is capped at the highest known level, never overflows past it") {
            AcrLevels.bump(AcrLevel.LOA3, steps = 1) shouldBe AcrLevel.LOA3
            AcrLevels.bump(AcrLevel.LOA2, steps = 10) shouldBe AcrLevel.LOA3
        }
    }
})
