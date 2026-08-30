package com.example.dpop.auth_sms.internal.authsmsuse

import com.example.dpop.auth_sms.internal.TanGenerator
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class AuthSmsUseFlowTest : BehaviorSpec({

    val tanGenerator = TanGenerator("test-pepper")
    val issued = tanGenerator.issue()
    val state = AuthSmsUseState(issued.hash, issued.expiresAt)

    given("a pending TAN") {
        `when`("nothing was submitted") {
            then("the state is unchanged") {
                AuthSmsUseFlow.decide(state, AuthSmsUseInput(), tanGenerator) shouldBe AuthSmsUseDecision.Unchanged
            }
        }

        `when`("the wrong tan was submitted") {
            then("it is rejected") {
                AuthSmsUseFlow.decide(state, AuthSmsUseInput("000000"), tanGenerator) shouldBe AuthSmsUseDecision.WrongTan
            }
        }

        `when`("the correct tan was submitted") {
            then("it completes") {
                AuthSmsUseFlow.decide(state, AuthSmsUseInput(issued.plainTan), tanGenerator) shouldBe AuthSmsUseDecision.Complete
            }
        }
    }

    given("describe()") {
        then("it asks for tan at step auth") {
            state.describe() shouldBe ("auth" to mapOf("missingFields" to listOf("tan")))
        }
    }

    given("toState()") {
        then("it reconstructs the pending TAN") {
            AuthSmsUseState.of(UUID.randomUUID(), issued.hash, issued.expiresAt) shouldBe state
        }
    }
})
