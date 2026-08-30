package com.example.dpop.auth_email.internal.enrollemail
import com.example.dpop.auth_email.internal.EmailCodeGenerator

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

/**
 * Pure unit test for the decision table alone - mirrors `auth_sms`'s `EnrollSmsFlowTest`.
 * [EnrollEmailToolHandlerTest] covers the other half: how a [EnrollEmailDecision] gets
 * translated into persistence, `account` writes and [com.example.dpop.tool_spi.ToolOutcome].
 */
class EnrollEmailFlowTest : BehaviorSpec({

    val emailCodeGenerator = EmailCodeGenerator("test-pepper")

    given("AwaitingEmail") {
        val state = EnrollEmailState.AwaitingEmail

        `when`("nothing was submitted") {
            then("the state is unchanged") {
                EnrollEmailFlow.decide(state, EnrollEmailInput(), emailCodeGenerator) shouldBe EnrollEmailDecision.Unchanged(state)
            }
        }

        `when`("an unrecognizable email was submitted") {
            then("it is rejected as invalid") {
                EnrollEmailFlow.decide(state, EnrollEmailInput(email = "not-an-email"), emailCodeGenerator) shouldBe
                    EnrollEmailDecision.InvalidEmail("not-an-email")
            }
        }

        `when`("a valid email was submitted") {
            then("a code is requested for the normalized address") {
                EnrollEmailFlow.decide(state, EnrollEmailInput(email = " Max@Example.com "), emailCodeGenerator) shouldBe
                    EnrollEmailDecision.RequestCode("max@example.com")
            }
        }

        `when`("a valid email AND a code were submitted together") {
            then("the email wins - there is no pending code yet for any code to be checked against") {
                EnrollEmailFlow.decide(state, EnrollEmailInput(email = "max@example.com", code = "123456"), emailCodeGenerator) shouldBe
                    EnrollEmailDecision.RequestCode("max@example.com")
            }
        }
    }

    given("AwaitingCode for max@example.com") {
        val issued = emailCodeGenerator.issue()
        val state = EnrollEmailState.AwaitingCode("max@example.com", issued.hash, issued.expiresAt)

        `when`("nothing was submitted") {
            then("the state is unchanged") {
                EnrollEmailFlow.decide(state, EnrollEmailInput(), emailCodeGenerator) shouldBe EnrollEmailDecision.Unchanged(state)
            }
        }

        `when`("the wrong code was submitted") {
            then("it is rejected without completing") {
                EnrollEmailFlow.decide(state, EnrollEmailInput(code = "000000"), emailCodeGenerator) shouldBe EnrollEmailDecision.WrongCode(state)
            }
        }

        `when`("the correct code was submitted") {
            then("the enrollment completes for this email") {
                EnrollEmailFlow.decide(state, EnrollEmailInput(code = issued.plainCode), emailCodeGenerator) shouldBe
                    EnrollEmailDecision.Complete("max@example.com")
            }
        }

        `when`("a different email AND the still-valid code for the old one were submitted together") {
            then("the new email wins - a changed address invalidates whatever code was pending for the old one") {
                EnrollEmailFlow.decide(state, EnrollEmailInput(email = "other@example.com", code = issued.plainCode), emailCodeGenerator) shouldBe
                    EnrollEmailDecision.RequestCode("other@example.com")
            }
        }
    }

    given("describe()") {
        `when`("AwaitingEmail") {
            then("it asks for email at step enroll, with the demo address") {
                val (step, fields) = EnrollEmailState.AwaitingEmail.describe()
                step shouldBe "enroll"
                fields["missingFields"] shouldBe listOf("email")
            }
        }

        `when`("AwaitingCode") {
            then("it asks for code at step codeInput") {
                val issued = emailCodeGenerator.issue()
                val state = EnrollEmailState.AwaitingCode("max@example.com", issued.hash, issued.expiresAt)
                state.describe() shouldBe ("codeInput" to mapOf("missingFields" to listOf("code")))
            }
        }
    }

    given("toState()") {
        val toolSessionId = UUID.randomUUID()

        `when`("no email was ever persisted") {
            then("it reconstructs AwaitingEmail") {
                EnrollEmailState.of(toolSessionId, null, null, null) shouldBe EnrollEmailState.AwaitingEmail
            }
        }

        `when`("an email with its code data was persisted") {
            then("it reconstructs AwaitingCode") {
                val issued = emailCodeGenerator.issue()
                EnrollEmailState.of(toolSessionId, "max@example.com", issued.hash, issued.expiresAt) shouldBe
                    EnrollEmailState.AwaitingCode("max@example.com", issued.hash, issued.expiresAt)
            }
        }
    }
})
