package com.example.dpop.auth_email.internal.authemailuse
import com.example.dpop.auth_email.internal.EmailCodeGenerator

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class AuthEmailUseFlowTest : BehaviorSpec({

    val emailCodeGenerator = EmailCodeGenerator("test-pepper")
    val issued = emailCodeGenerator.issue()
    val state = AuthEmailUseState(issued.hash, issued.expiresAt)

    given("a pending code") {
        `when`("nothing was submitted") {
            then("the state is unchanged") {
                AuthEmailUseFlow.decide(state, AuthEmailUseInput(), emailCodeGenerator) shouldBe AuthEmailUseDecision.Unchanged
            }
        }

        `when`("the wrong code was submitted") {
            then("it is rejected") {
                AuthEmailUseFlow.decide(state, AuthEmailUseInput("000000"), emailCodeGenerator) shouldBe AuthEmailUseDecision.WrongCode
            }
        }

        `when`("the correct code was submitted") {
            then("it completes") {
                AuthEmailUseFlow.decide(state, AuthEmailUseInput(issued.plainCode), emailCodeGenerator) shouldBe AuthEmailUseDecision.Complete
            }
        }
    }

    given("describe()") {
        then("it asks for code at step auth") {
            state.describe() shouldBe ("auth" to mapOf("missingFields" to listOf("code")))
        }
    }

    given("toState()") {
        then("it reconstructs the pending code") {
            AuthEmailUseState.of(UUID.randomUUID(), issued.hash, issued.expiresAt) shouldBe state
        }
    }
})
