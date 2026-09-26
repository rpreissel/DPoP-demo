package com.example.dpop.auth_sms.internal.authsms

import com.example.dpop.auth_sms.internal.TanGenerator
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.util.UUID
import com.example.dpop.tool_spi.MissingFields

class AuthSmsFlowTest : BehaviorSpec({

    val tanGenerator = TanGenerator("test-pepper")
    val issued = tanGenerator.issue()
    val state = AuthSmsState(issued.hash, issued.expiresAt)

    given("a pending TAN") {
        `when`("nothing was submitted") {
            then("the state is unchanged") {
                AuthSmsFlow.decide(state, AuthSmsInput(), tanGenerator) shouldBe AuthSmsDecision.Unchanged
            }
        }

        `when`("the wrong tan was submitted") {
            then("it is rejected") {
                AuthSmsFlow.decide(state, AuthSmsInput("000000"), tanGenerator) shouldBe AuthSmsDecision.WrongTan
            }
        }

        `when`("the correct tan was submitted") {
            then("it completes") {
                AuthSmsFlow.decide(state, AuthSmsInput(issued.plainTan), tanGenerator) shouldBe AuthSmsDecision.Complete
            }
        }
    }

    given("describe()") {
        then("it asks for tan at step auth") {
            state.describe() shouldBe ("auth" to MissingFields(listOf("tan")))
        }
    }

    given("toState()") {
        then("it reconstructs the pending TAN") {
            AuthSmsState.of(UUID.randomUUID(), issued.hash, issued.expiresAt) shouldBe state
        }
    }
})
