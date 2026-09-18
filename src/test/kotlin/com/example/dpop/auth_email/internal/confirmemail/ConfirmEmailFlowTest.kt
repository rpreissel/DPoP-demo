package com.example.dpop.auth_email.internal.confirmemail
import com.example.dpop.auth_email.internal.EmailCodeGenerator

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

/**
 * Pure unit test for the decision table alone - mirrors `auth_sms`'s `EnrollSmsFlowTest`.
 * [ConfirmEmailToolHandlerTest] covers the other half: how a [ConfirmEmailDecision] gets
 * translated into persistence, `account` writes and [com.example.dpop.tool_spi.ToolOutcome].
 */
class ConfirmEmailFlowTest : BehaviorSpec({

    val emailCodeGenerator = EmailCodeGenerator("test-pepper")

    given("AwaitingEmail") {
        val state = ConfirmEmailState.AwaitingEmail

        `when`("nothing was submitted") {
            then("the state is unchanged") {
                ConfirmEmailFlow.decide(state, ConfirmEmailInput(), emailCodeGenerator) shouldBe ConfirmEmailDecision.Unchanged(state)
            }
        }

        `when`("an unrecognizable email was submitted") {
            then("it is rejected as invalid") {
                ConfirmEmailFlow.decide(state, ConfirmEmailInput(email = "not-an-email"), emailCodeGenerator) shouldBe
                    ConfirmEmailDecision.InvalidEmail("not-an-email")
            }
        }

        `when`("a valid email was submitted") {
            then("a code is requested for the normalized address") {
                ConfirmEmailFlow.decide(state, ConfirmEmailInput(email = " Max@Example.com "), emailCodeGenerator) shouldBe
                    ConfirmEmailDecision.RequestCode("max@example.com")
            }
        }

        `when`("a valid email AND a code were submitted together") {
            then("the email wins - there is no pending code yet for any code to be checked against") {
                ConfirmEmailFlow.decide(state, ConfirmEmailInput(email = "max@example.com", code = "123456"), emailCodeGenerator) shouldBe
                    ConfirmEmailDecision.RequestCode("max@example.com")
            }
        }
    }

    given("AwaitingCode for max@example.com") {
        val issued = emailCodeGenerator.issue()
        val state = ConfirmEmailState.AwaitingCode("max@example.com", issued.hash, issued.expiresAt)

        `when`("nothing was submitted") {
            then("the state is unchanged") {
                ConfirmEmailFlow.decide(state, ConfirmEmailInput(), emailCodeGenerator) shouldBe ConfirmEmailDecision.Unchanged(state)
            }
        }

        `when`("the wrong code was submitted") {
            then("it is rejected without completing") {
                ConfirmEmailFlow.decide(state, ConfirmEmailInput(code = "000000"), emailCodeGenerator) shouldBe ConfirmEmailDecision.WrongCode(state)
            }
        }

        `when`("the correct code was submitted") {
            then("the enrollment completes for this email") {
                ConfirmEmailFlow.decide(state, ConfirmEmailInput(code = issued.plainCode), emailCodeGenerator) shouldBe
                    ConfirmEmailDecision.Complete("max@example.com")
            }
        }

        `when`("a different email AND the still-valid code for the old one were submitted together") {
            then("the new email wins - a changed address invalidates whatever code was pending for the old one") {
                ConfirmEmailFlow.decide(state, ConfirmEmailInput(email = "other@example.com", code = issued.plainCode), emailCodeGenerator) shouldBe
                    ConfirmEmailDecision.RequestCode("other@example.com")
            }
        }
    }

    given("describe()") {
        `when`("AwaitingEmail") {
            then("it asks for email at step input, with the demo address") {
                val (step, fields) = ConfirmEmailState.AwaitingEmail.describe()
                step shouldBe "input"
                fields["missingFields"] shouldBe listOf("email")
            }
        }

        `when`("AwaitingCode") {
            then("it asks for code at step codeInput") {
                val issued = emailCodeGenerator.issue()
                val state = ConfirmEmailState.AwaitingCode("max@example.com", issued.hash, issued.expiresAt)
                state.describe() shouldBe ("codeInput" to mapOf("missingFields" to listOf("code")))
            }
        }
    }

    given("toState()") {
        val toolSessionId = UUID.randomUUID()

        `when`("no email was ever persisted") {
            then("it reconstructs AwaitingEmail") {
                ConfirmEmailState.of(toolSessionId, null, null, null) shouldBe ConfirmEmailState.AwaitingEmail
            }
        }

        `when`("an email with its code data was persisted") {
            then("it reconstructs AwaitingCode") {
                val issued = emailCodeGenerator.issue()
                ConfirmEmailState.of(toolSessionId, "max@example.com", issued.hash, issued.expiresAt) shouldBe
                    ConfirmEmailState.AwaitingCode("max@example.com", issued.hash, issued.expiresAt)
            }
        }
    }
})
