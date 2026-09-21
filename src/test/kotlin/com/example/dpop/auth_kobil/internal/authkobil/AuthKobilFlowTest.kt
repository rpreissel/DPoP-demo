package com.example.dpop.auth_kobil.internal.authkobil

import com.example.dpop.kobil_mock.KobilOtpVerification
import com.example.dpop.kobil_mock.KobilRisk
import com.example.dpop.tool_api.UserVerification
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/** The redemption decision, in isolation - no Spring, no repositories, no provider. */
class AuthKobilFlowTest : BehaviorSpec({

    val enrolled = "dev-enrolled"
    val blocking = setOf(KobilRisk.ROOTED, KobilRisk.APP_TAMPERED)

    given("no live PIN release") {
        then("that is the answer, whatever the OTP says - an unlock was never presented") {
            AuthKobilFlow.decide(
                verification = KobilOtpVerification(enrolled, emptySet()),
                enrolledDeviceId = enrolled,
                release = null,
                blockingRisks = blocking,
            ) shouldBe AuthKobilDecision.NotReleased
        }
    }

    given("a live release") {
        then("nothing back from the provider means the OTP is unknown or already spent") {
            AuthKobilFlow.decide(null, enrolled, UserVerification.BIOMETRIC, blocking) shouldBe
                AuthKobilDecision.OtpInvalid
        }

        then("an assertion from another device is refused before any risk is even considered") {
            AuthKobilFlow.decide(
                KobilOtpVerification("dev-other", setOf(KobilRisk.ROOTED)),
                enrolled,
                UserVerification.BIOMETRIC,
                blocking,
            ) shouldBe AuthKobilDecision.WrongDevice
        }

        then("a blocking signal is refused, and the decision names which ones blocked") {
            val decision = AuthKobilFlow.decide(
                KobilOtpVerification(enrolled, setOf(KobilRisk.ROOTED, KobilRisk.OS_OUTDATED)),
                enrolled,
                UserVerification.BIOMETRIC,
                blocking,
            )
            decision.shouldBeInstanceOf<AuthKobilDecision.RiskRejected>()
            decision.risks shouldBe setOf(KobilRisk.ROOTED)
        }

        then("a signal outside the blocking set does not stand in the way") {
            AuthKobilFlow.decide(
                KobilOtpVerification(enrolled, setOf(KobilRisk.OS_OUTDATED)),
                enrolled,
                UserVerification.PIN,
                blocking,
            ) shouldBe AuthKobilDecision.Complete(UserVerification.PIN)
        }

        then("the access means that unlocked the PIN is what the completed run carries forward") {
            AuthKobilFlow.decide(
                KobilOtpVerification(enrolled, emptySet()),
                enrolled,
                UserVerification.BIOMETRIC,
                blocking,
            ) shouldBe AuthKobilDecision.Complete(UserVerification.BIOMETRIC)
        }
    }
})
