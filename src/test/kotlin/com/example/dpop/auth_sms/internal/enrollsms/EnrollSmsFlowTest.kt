package com.example.dpop.auth_sms.internal.enrollsms
import com.example.dpop.auth_sms.internal.TanGenerator

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.UUID

/**
 * Pure unit test for the decision table alone: no Spring, no repositories, no [EnrollSmsToolHandler] -
 * just [EnrollSmsState] in, [EnrollSmsDecision] out. [EnrollSmsToolHandlerTest] covers the other half,
 * how a [EnrollSmsDecision] gets translated into persistence and [com.example.dpop.tool_spi.ToolOutcome].
 */
class EnrollSmsFlowTest : BehaviorSpec({

    // Explicit pepper so issue()/matches() stay reproducible within the test run.
    val tanGenerator = TanGenerator("test-pepper")

    given("AwaitingPhoneNumber") {
        val state = EnrollSmsState.AwaitingPhoneNumber

        `when`("nothing was submitted") {
            then("the state is unchanged") {
                EnrollSmsFlow.decide(state, EnrollSmsInput(), tanGenerator) shouldBe EnrollSmsDecision.Unchanged(state)
            }
        }

        `when`("an unrecognizable phone number was submitted") {
            then("it is rejected as invalid") {
                EnrollSmsFlow.decide(state, EnrollSmsInput(phoneNumber = "not-a-number"), tanGenerator) shouldBe
                    EnrollSmsDecision.InvalidPhoneNumber("not-a-number")
            }
        }

        `when`("a valid phone number was submitted") {
            then("a TAN is sent to the normalized number") {
                EnrollSmsFlow.decide(state, EnrollSmsInput(phoneNumber = "+49 170 1234567"), tanGenerator) shouldBe
                    EnrollSmsDecision.SendTan("+491701234567")
            }
        }

        `when`("a valid phone number AND a tan were submitted together") {
            then("the phone number wins - there is no pending TAN yet for any tan to be checked against") {
                val decision = EnrollSmsFlow.decide(state, EnrollSmsInput(phoneNumber = "+49 170 1234567", tan = "123456"), tanGenerator)
                decision shouldBe EnrollSmsDecision.SendTan("+491701234567")
            }
        }
    }

    given("AwaitingTan for +491701234567") {
        val issued = tanGenerator.issue()
        val state = EnrollSmsState.AwaitingTan("+491701234567", issued.hash, issued.expiresAt)

        `when`("nothing was submitted") {
            then("the state is unchanged") {
                EnrollSmsFlow.decide(state, EnrollSmsInput(), tanGenerator) shouldBe EnrollSmsDecision.Unchanged(state)
            }
        }

        `when`("the wrong tan was submitted") {
            then("it is rejected without completing") {
                EnrollSmsFlow.decide(state, EnrollSmsInput(tan = "000000"), tanGenerator) shouldBe EnrollSmsDecision.WrongTan(state)
            }
        }

        `when`("the correct tan was submitted") {
            then("the enrollment completes for this phone number") {
                EnrollSmsFlow.decide(state, EnrollSmsInput(tan = issued.plainTan), tanGenerator) shouldBe
                    EnrollSmsDecision.Complete("+491701234567")
            }
        }

        `when`("a different phone number AND the still-valid tan for the old one were submitted together") {
            then("the new number wins - a changed number invalidates whatever TAN was pending for the old one") {
                val decision = EnrollSmsFlow.decide(state, EnrollSmsInput(phoneNumber = "+49 171 9999999", tan = issued.plainTan), tanGenerator)
                decision shouldBe EnrollSmsDecision.SendTan("+491719999999")
            }
        }

        `when`("an unrecognizable phone number was submitted instead") {
            then("it is rejected as invalid, the pending TAN untouched") {
                EnrollSmsFlow.decide(state, EnrollSmsInput(phoneNumber = "not-a-number"), tanGenerator) shouldBe
                    EnrollSmsDecision.InvalidPhoneNumber("not-a-number")
            }
        }
    }

    given("describe()") {
        `when`("AwaitingPhoneNumber") {
            then("it asks for phoneNumber at step enroll") {
                EnrollSmsState.AwaitingPhoneNumber.describe() shouldBe
                    ("enroll" to mapOf("missingFields" to listOf("phoneNumber")))
            }
        }

        `when`("AwaitingTan") {
            then("it asks for tan at step tanInput") {
                val issued = tanGenerator.issue()
                val state = EnrollSmsState.AwaitingTan("+491701234567", issued.hash, issued.expiresAt)
                state.describe() shouldBe ("tanInput" to mapOf("missingFields" to listOf("tan")))
            }
        }
    }

    given("toState()") {
        val toolSessionId = UUID.randomUUID()

        `when`("no phoneNumber was ever persisted") {
            then("it reconstructs AwaitingPhoneNumber") {
                EnrollSmsState.of(toolSessionId, null, null, null) shouldBe EnrollSmsState.AwaitingPhoneNumber
            }
        }

        `when`("a phoneNumber with its tan data was persisted") {
            then("it reconstructs AwaitingTan") {
                val issued = tanGenerator.issue()
                EnrollSmsState.of(toolSessionId, "+491701234567", issued.hash, issued.expiresAt) shouldBe
                    EnrollSmsState.AwaitingTan("+491701234567", issued.hash, issued.expiresAt)
            }
        }
    }

    given("a WrongTan decision") {
        then("it carries the unchanged AwaitingTan state, not a fresh one") {
            val issued = tanGenerator.issue()
            val state = EnrollSmsState.AwaitingTan("+491701234567", issued.hash, issued.expiresAt)
            val decision = EnrollSmsFlow.decide(state, EnrollSmsInput(tan = "000000"), tanGenerator)
            decision.shouldBeInstanceOf<EnrollSmsDecision.WrongTan>()
            (decision as EnrollSmsDecision.WrongTan).state shouldBe state
        }
    }
})
