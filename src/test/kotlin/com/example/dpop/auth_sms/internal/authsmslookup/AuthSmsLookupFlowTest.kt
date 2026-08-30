package com.example.dpop.auth_sms.internal.authsmslookup
import com.example.dpop.auth_sms.internal.TanGenerator

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

/**
 * Pure unit test for the tan-vs-state decision. [AuthSmsLookupToolHandlerTest] would cover
 * persistence/enrollment-resolution wiring - not yet added, see class doc there.
 */
class AuthSmsLookupFlowTest : BehaviorSpec({

    val tanGenerator = TanGenerator("test-pepper")

    given("AwaitingEmail") {
        val state = AuthSmsLookupState.AwaitingEmail

        `when`("a tan is submitted before any email was ever resolved") {
            then("the state is unchanged - not a wrong-tan failure") {
                AuthSmsLookupFlow.decideTan(state, "000000", tanGenerator) shouldBe AuthSmsLookupDecision.Unchanged(state)
            }
        }
    }

    given("AwaitingTan for a resolved account") {
        val issued = tanGenerator.issue()
        val state = AuthSmsLookupState.AwaitingTan(accountId = 42L, issued.hash, issued.expiresAt)

        `when`("nothing was submitted") {
            then("the state is unchanged") {
                AuthSmsLookupFlow.decideTan(state, null, tanGenerator) shouldBe AuthSmsLookupDecision.Unchanged(state)
            }
        }

        `when`("the wrong tan was submitted") {
            then("it is rejected, naming the account for the throttle") {
                AuthSmsLookupFlow.decideTan(state, "000000", tanGenerator) shouldBe AuthSmsLookupDecision.WrongTan(42L)
            }
        }

        `when`("the correct tan was submitted") {
            then("it completes for that account") {
                AuthSmsLookupFlow.decideTan(state, issued.plainTan, tanGenerator) shouldBe AuthSmsLookupDecision.Complete(42L)
            }
        }
    }

    given("AwaitingTan for an unresolved email (enumeration protection)") {
        val issued = tanGenerator.issue()
        val state = AuthSmsLookupState.AwaitingTan(accountId = null, issued.hash, issued.expiresAt)

        `when`("the tan that would have matched a real account is submitted") {
            then("it still fails - there is no account to complete for") {
                AuthSmsLookupFlow.decideTan(state, issued.plainTan, tanGenerator) shouldBe AuthSmsLookupDecision.WrongTan(null)
            }
        }
    }

    given("describe()") {
        `when`("AwaitingEmail") {
            then("it asks for email at step auth") {
                val (step, fields) = AuthSmsLookupState.AwaitingEmail.describe()
                step shouldBe "auth"
                fields["missingFields"] shouldBe listOf("email")
            }
        }

        `when`("AwaitingTan") {
            then("it asks for tan at step tanInput") {
                val issued = tanGenerator.issue()
                val state = AuthSmsLookupState.AwaitingTan(42L, issued.hash, issued.expiresAt)
                state.describe() shouldBe ("tanInput" to mapOf("missingFields" to listOf("tan")))
            }
        }
    }

    given("toState()") {
        val toolSessionId = UUID.randomUUID()

        `when`("no tan was ever issued") {
            then("it reconstructs AwaitingEmail") {
                AuthSmsLookupState.of(toolSessionId, null, null, null) shouldBe AuthSmsLookupState.AwaitingEmail
            }
        }

        `when`("a tan was issued for a resolved account") {
            then("it reconstructs AwaitingTan") {
                val issued = tanGenerator.issue()
                AuthSmsLookupState.of(toolSessionId, 42L, issued.hash, issued.expiresAt) shouldBe
                    AuthSmsLookupState.AwaitingTan(42L, issued.hash, issued.expiresAt)
            }
        }
    }
})
