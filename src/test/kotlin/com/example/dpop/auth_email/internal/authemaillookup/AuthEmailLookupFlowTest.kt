package com.example.dpop.auth_email.internal.authemaillookup
import com.example.dpop.auth_email.internal.EmailCodeGenerator

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

/** Pure unit test for the code-vs-state decision - mirrors `auth_sms`'s `AuthSmsLookupFlowTest`. */
class AuthEmailLookupFlowTest : BehaviorSpec({

    val emailCodeGenerator = EmailCodeGenerator("test-pepper")

    given("AwaitingEmail") {
        val state = AuthEmailLookupState.AwaitingEmail

        `when`("a code is submitted before any email was ever resolved") {
            then("the state is unchanged - not a wrong-code failure") {
                AuthEmailLookupFlow.decideCode(state, "000000", emailCodeGenerator) shouldBe AuthEmailLookupDecision.Unchanged(state)
            }
        }
    }

    given("AwaitingCode for a resolved account") {
        val issued = emailCodeGenerator.issue()
        val state = AuthEmailLookupState.AwaitingCode(accountId = 42L, issued.hash, issued.expiresAt)

        `when`("nothing was submitted") {
            then("the state is unchanged") {
                AuthEmailLookupFlow.decideCode(state, null, emailCodeGenerator) shouldBe AuthEmailLookupDecision.Unchanged(state)
            }
        }

        `when`("the wrong code was submitted") {
            then("it is rejected, naming the account for the throttle") {
                AuthEmailLookupFlow.decideCode(state, "000000", emailCodeGenerator) shouldBe AuthEmailLookupDecision.WrongCode(42L)
            }
        }

        `when`("the correct code was submitted") {
            then("it completes for that account") {
                AuthEmailLookupFlow.decideCode(state, issued.plainCode, emailCodeGenerator) shouldBe AuthEmailLookupDecision.Complete(42L)
            }
        }
    }

    given("AwaitingCode for an unresolved email (enumeration protection)") {
        val issued = emailCodeGenerator.issue()
        val state = AuthEmailLookupState.AwaitingCode(accountId = null, issued.hash, issued.expiresAt)

        `when`("the code that would have matched a real account is submitted") {
            then("it still fails - there is no account to complete for") {
                AuthEmailLookupFlow.decideCode(state, issued.plainCode, emailCodeGenerator) shouldBe AuthEmailLookupDecision.WrongCode(null)
            }
        }
    }

    given("describe()") {
        `when`("AwaitingEmail") {
            then("it asks for email at step auth") {
                val (step, fields) = AuthEmailLookupState.AwaitingEmail.describe()
                step shouldBe "auth"
                fields["missingFields"] shouldBe listOf("email")
            }
        }

        `when`("AwaitingCode") {
            then("it asks for code at step codeInput") {
                val issued = emailCodeGenerator.issue()
                val state = AuthEmailLookupState.AwaitingCode(42L, issued.hash, issued.expiresAt)
                state.describe() shouldBe ("codeInput" to mapOf("missingFields" to listOf("code")))
            }
        }
    }

    given("toState()") {
        val toolSessionId = UUID.randomUUID()

        `when`("no code was ever issued") {
            then("it reconstructs AwaitingEmail") {
                AuthEmailLookupState.of(toolSessionId, null, null, null) shouldBe AuthEmailLookupState.AwaitingEmail
            }
        }

        `when`("a code was issued for a resolved account") {
            then("it reconstructs AwaitingCode") {
                val issued = emailCodeGenerator.issue()
                AuthEmailLookupState.of(toolSessionId, 42L, issued.hash, issued.expiresAt) shouldBe
                    AuthEmailLookupState.AwaitingCode(42L, issued.hash, issued.expiresAt)
            }
        }
    }
})
