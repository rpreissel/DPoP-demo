package com.example.dpop.auth_email.internal.authemail
import com.example.dpop.auth_email.internal.EmailCodeGenerator

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.util.UUID
import com.example.dpop.tool_spi.MissingFields

class AuthEmailFlowTest : BehaviorSpec({

    val emailCodeGenerator = EmailCodeGenerator("test-pepper")
    val issued = emailCodeGenerator.issue()
    val state = AuthEmailState(issued.hash, issued.expiresAt)

    given("a pending code") {
        `when`("nothing was submitted") {
            then("the state is unchanged") {
                AuthEmailFlow.decide(state, AuthEmailInput(), emailCodeGenerator) shouldBe AuthEmailDecision.Unchanged
            }
        }

        `when`("the wrong code was submitted") {
            then("it is rejected") {
                AuthEmailFlow.decide(state, AuthEmailInput("000000"), emailCodeGenerator) shouldBe AuthEmailDecision.WrongCode
            }
        }

        `when`("the correct code was submitted") {
            then("it completes") {
                AuthEmailFlow.decide(state, AuthEmailInput(issued.plainCode), emailCodeGenerator) shouldBe AuthEmailDecision.Complete
            }
        }
    }

    given("describe()") {
        then("it asks for code at step auth") {
            state.describe() shouldBe ("auth" to MissingFields(listOf("code")))
        }
    }

    given("toState()") {
        then("it reconstructs the pending code") {
            AuthEmailState.of(UUID.randomUUID(), issued.hash, issued.expiresAt) shouldBe state
        }
    }
})
