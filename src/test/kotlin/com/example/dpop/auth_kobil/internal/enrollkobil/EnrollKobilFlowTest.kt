package com.example.dpop.auth_kobil.internal.enrollkobil

import com.example.dpop.tool_api.UserVerification
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class EnrollKobilFlowTest : BehaviorSpec({

    given("a confirmation with everything present") {
        then("consenting to biometrics writes a credential that reports the biometric access means") {
            val decision = EnrollKobilFlow.decide(true, biometricConsent = true, deviceId = "dev-1")
            decision shouldBe EnrollKobilDecision.Enroll("dev-1", biometricConsent = true)
            (decision as EnrollKobilDecision.Enroll).userVerification shouldBe UserVerification.BIOMETRIC
        }

        then("declining reports the password access means - and the consent is the only input for it") {
            val decision = EnrollKobilFlow.decide(true, biometricConsent = false, deviceId = "dev-1")
            decision shouldBe EnrollKobilDecision.Enroll("dev-1", biometricConsent = false)
            (decision as EnrollKobilDecision.Enroll).userVerification shouldBe UserVerification.PIN
        }
    }

    given("an incomplete confirmation") {
        then("an unconfirmed activation changes nothing") {
            EnrollKobilFlow.decide(null, true, "dev-1") shouldBe EnrollKobilDecision.Unchanged
            EnrollKobilFlow.decide(false, true, "dev-1") shouldBe EnrollKobilDecision.Unchanged
        }

        then("a missing consent changes nothing - there is no default for a consent") {
            EnrollKobilFlow.decide(true, null, "dev-1") shouldBe EnrollKobilDecision.Unchanged
        }

        then("a claim to have activated while the provider knows no device changes nothing") {
            // Deliberately Unchanged, not a failure: a client that reloaded mid-setup has not
            // guessed at anything, so the journey's attempt budget must not be charged for it.
            EnrollKobilFlow.decide(true, true, null) shouldBe EnrollKobilDecision.Unchanged
        }
    }
})
