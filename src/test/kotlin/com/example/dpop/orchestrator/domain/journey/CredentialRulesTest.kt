package com.example.dpop.orchestrator.domain.journey

import com.example.dpop.account.AuthMethodView
import com.example.dpop.orchestrator.domain.AcrLevels
import com.example.dpop.orchestrator.domain.AuthIntent
import com.example.dpop.tool_spi.AcrLevel
import com.example.dpop.tool_spi.CallerKeyBinding
import com.example.dpop.tool_spi.EnrollmentRef
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/** The credential rules of the acting phase, as tables (docs/ideen/fachkern-und-technik-trennen.md). */
class CredentialRulesTest : BehaviorSpec({

    fun instance(id: String, enrolledUnder: String?, key: String? = null) =
        AuthMethodView(id, "device", true, null, enrolledUnder, key?.let { mapOf("key" to it) }, EnrollmentRef("device", id))
    val onCallerKey = CallerKeyBinding { details, caller -> details?.get("key") == caller }

    given("the level a new credential is written under (ADR-5)") {
        then("nothing proven yet means the baseline, never the tool's own strength") {
            levelToWriteUnder(AcrLevel.NONE) shouldBe AcrLevels.DEFAULT_REQUIRED_ACR
        }
        then("otherwise exactly what the session had proven - no more") {
            levelToWriteUnder(AcrLevel.LOA2) shouldBe AcrLevel.LOA2
        }
    }

    given("the level a proof counts at") {
        then("capped by the instance on the caller's key, not by the first active one") {
            proofLevel(listOf(instance("a", "loa1", "k1"), instance("b", "loa2", "k2")), onCallerKey, "k2", AcrLevel.LOA2) shouldBe AcrLevel.LOA2
            proofLevel(listOf(instance("a", "loa1", "k1"), instance("b", "loa2", "k2")), onCallerKey, "k1", AcrLevel.LOA2) shouldBe AcrLevel.LOA1
        }
        then("when it cannot tell which instance was used, the lowest cap applies") {
            proofLevel(listOf(instance("a", "loa1", "k1"), instance("b", "loa2", "k2")), onCallerKey, "k9", AcrLevel.LOA2) shouldBe AcrLevel.LOA1
            proofLevel(listOf(instance("a", "loa1"), instance("b", "loa2")), null, null, AcrLevel.LOA2) shouldBe AcrLevel.LOA1
        }
        then("never above what the tool itself achieved") {
            proofLevel(listOf(instance("a", "loa2")), null, null, AcrLevel.LOA1) shouldBe AcrLevel.LOA1
        }
        then("without an active instance there is nothing to count against") {
            shouldThrow<IllegalStateException> { proofLevel(emptyList(), null, null, AcrLevel.LOA1) }
        }
    }

    given("the device link that follows from succeeding") {
        val implicit = AuthIntent.entries.first { it.bindsDeviceImplicitly }
        val explicitOnly = AuthIntent.entries.first { !it.bindsDeviceImplicitly }
        then("a free device, or one already linked here, is linked") {
            linksDeviceImplicitly(implicit, linkedTo = null, accountId = 1) shouldBe true
            linksDeviceImplicitly(implicit, linkedTo = 1, accountId = 1) shouldBe true
        }
        then("a device linked to ANOTHER account is never rebound implicitly") {
            linksDeviceImplicitly(implicit, linkedTo = 2, accountId = 1) shouldBe false
        }
        then("an intent that does not bind devices never does") {
            linksDeviceImplicitly(explicitOnly, linkedTo = null, accountId = 1) shouldBe false
        }
    }
})
