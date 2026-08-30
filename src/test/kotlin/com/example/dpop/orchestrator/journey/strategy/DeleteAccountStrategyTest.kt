package com.example.dpop.orchestrator.journey.strategy

import com.example.dpop.auth_sms.AuthSmsUseDescriptor
import com.example.dpop.id_fsc.IdentFscDescriptor
import com.example.dpop.orchestrator.journey.AuthIntent
import com.example.dpop.orchestrator.journey.Decision
import com.example.dpop.orchestrator.journey.Effect
import com.example.dpop.orchestrator.journey.JourneyEvent
import com.example.dpop.orchestrator.journey.state.DeleteAccountState
import com.example.dpop.orchestrator.journey.strategy.StrategyTestFixtures.account
import com.example.dpop.orchestrator.journey.strategy.StrategyTestFixtures.ctx
import com.example.dpop.orchestrator.journey.strategy.StrategyTestFixtures.method
import com.example.dpop.orchestrator.policy.AuthEvidence
import com.example.dpop.orchestrator.session.ChannelState
import com.example.dpop.tool_spi.FactorType
import com.example.dpop.tool_spi.ToolOutcome
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Pure unit coverage of [DeleteAccountStrategy] - self-service account deletion
 * (docs/04-orchestrierung.md #3, docs/05-api.md "Account löschen"). No Spring context, no HTTP.
 */
class DeleteAccountStrategyTest : BehaviorSpec({

    val strategy = DeleteAccountStrategy()

    given("the intent") {
        then("is DELETE_ACCOUNT") {
            strategy.intent shouldBe AuthIntent.DELETE_ACCOUNT
        }
    }

    given("initialState") {
        then("is ConfirmPending - the yes/no confirmation always comes first, unconditionally") {
            strategy.initialState(ctx()) shouldBe DeleteAccountState.ConfirmPending
        }
    }

    given("interpret") {
        then("Authenticated only re-proves presence, never re-establishes or adopts an account") {
            strategy.interpret(DeleteAccountState.ConfirmPending, AuthSmsUseDescriptor, ToolOutcome.Completed.Authenticated(amr = listOf("sms"))) shouldBe
                Effect.AcceptProof(useOutcomeAccount = false, bindDevice = false)
        }

        then("Identified is not offered by this intent") {
            shouldThrow<IllegalStateException> {
                strategy.interpret(DeleteAccountState.ConfirmPending, IdentFscDescriptor, ToolOutcome.Completed.Identified(personId = 1L))
            }
        }

        then("Enrolled is not offered by this intent") {
            shouldThrow<IllegalStateException> {
                strategy.interpret(DeleteAccountState.ConfirmPending, AuthSmsUseDescriptor, ToolOutcome.Completed.Enrolled(enrollmentRef = com.example.dpop.tool_spi.EnrollmentRef("sms", "ref")))
            }
        }
    }

    given("ConfirmPending, just started") {
        then("unconditionally re-presents the confirmation prompt") {
            strategy.decide(DeleteAccountState.ConfirmPending, JourneyEvent.Started, ctx()) shouldBe Decision.Advance(DeleteAccountState.ConfirmPending)
        }
    }

    given("ConfirmPending, declined") {
        then("cancels - no gate is ever evaluated before an explicit yes") {
            strategy.decide(DeleteAccountState.ConfirmPending, JourneyEvent.Answered("decline"), ctx()) shouldBe Decision.Cancel
        }
    }

    given("ConfirmPending, an unrecognized answer") {
        then("fails loudly rather than guessing") {
            shouldThrow<IllegalStateException> { strategy.decide(DeleteAccountState.ConfirmPending, JourneyEvent.Answered("maybe"), ctx()) }
        }
    }

    given("ConfirmPending, accepted") {
        `when`("the session does not yet carry loa2") {
            val acc = account(method("sms", "loa1"))
            val theCtx = ctx(account = acc, evidence = AuthEvidence(listOf("sms"), setOf(FactorType.POSSESSION)))
            then("the loa2 gate parks the wish and demands a step-up first - only NOW, never before accepting") {
                strategy.decide(DeleteAccountState.ConfirmPending, JourneyEvent.Answered("accept"), theCtx) shouldBe
                    Decision.RequireSubJourney(AuthIntent.STEP_UP, "loa2", resumeWith = DeleteAccountState.ConfirmPending)
            }
        }

        `when`("the session already carries loa2") {
            // device is the only tool whose own maxAcr reaches loa2 alone (sms/password/email cap
            // at loa1) - so this is the only single-method way to seed "already at loa2" evidence.
            val acc = account(method("device", "loa2", details = StrategyTestFixtures.deviceDetails()))
            val theCtx = ctx(account = acc, evidence = AuthEvidence(listOf("device"), setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE, FactorType.INHERENCE)), acrFloor = "loa1")
            then("still demands a fresh re-confirmation of any active factor - unlike STEP_UP, evidence of unknown age is never enough on its own") {
                val decision = strategy.decide(DeleteAccountState.ConfirmPending, JourneyEvent.Answered("accept"), theCtx)
                decision.shouldBeInstanceOf<Decision.Advance>()
                val to = (decision as Decision.Advance).to
                to.shouldBeInstanceOf<DeleteAccountState.ConfirmationRequired>()
                (to as DeleteAccountState.ConfirmationRequired).offered shouldContainExactlyInAnyOrder listOf("auth-device")
            }
        }
    }

    given("ConfirmPending, resumed after a sub-journey (SubJourneyFinished)") {
        `when`("it was the gate's own STEP_UP, and it reached loa2") {
            val acc = account(method("sms", "loa2"))
            val theCtx = ctx(account = acc)
            then("deletes right away - that fresh proof already IS the re-confirmation, no second one demanded") {
                val event = JourneyEvent.SubJourneyFinished(AuthIntent.STEP_UP, achievedAcr = "loa2")
                strategy.decide(DeleteAccountState.ConfirmPending, event, theCtx) shouldBe Decision.DeleteAccount(acc.accountId)
            }
        }

        `when`("it was a STEP_UP that fell short of loa2") {
            val acc = account(method("sms", "loa2"))
            val theCtx = ctx(account = acc)
            then("does not delete and does not fall back to a lesser reconfirmation either - that would let a session stuck below loa2 delete via the very factor that couldn't reach it") {
                val event = JourneyEvent.SubJourneyFinished(AuthIntent.STEP_UP, achievedAcr = "loa1")
                strategy.decide(DeleteAccountState.ConfirmPending, event, theCtx) shouldBe Decision.Cancel
            }
        }

        `when`("it was a different sub-journey entirely - never assumed to be the gate's own") {
            val acc = account(method("sms", "loa2"))
            val theCtx = ctx(account = acc)
            then("does not delete") {
                val event = JourneyEvent.SubJourneyFinished(AuthIntent.RE_IDENTIFY, achievedAcr = "loa3")
                strategy.decide(DeleteAccountState.ConfirmPending, event, theCtx) shouldBe Decision.Cancel
            }
        }

        `when`("the gate's own STEP_UP was declined instead (SubJourneyCancelled)") {
            val acc = account(method("sms", "loa2"))
            val theCtx = ctx(account = acc)
            then("does not delete - same as falling short, not a lesser fallback") {
                val event = JourneyEvent.SubJourneyCancelled(AuthIntent.STEP_UP)
                strategy.decide(DeleteAccountState.ConfirmPending, event, theCtx) shouldBe Decision.Cancel
            }
        }
    }

    given("ConfirmationRequired, more than one offered candidate") {
        val state = DeleteAccountState.ConfirmationRequired(listOf("auth-sms", "auth-password"))
        then("abandoning one keeps the choice among the rest") {
            strategy.decide(state, JourneyEvent.Abandoned(AuthSmsUseDescriptor), ctx()) shouldBe
                Decision.Advance(state.copy(declined = setOf("auth-sms"), active = null))
        }
    }

    given("ConfirmationRequired, the last offered candidate is abandoned") {
        val state = DeleteAccountState.ConfirmationRequired(listOf("auth-sms"))
        then("cancels - the account is never deleted just because every option was declined") {
            strategy.decide(state, JourneyEvent.Abandoned(AuthSmsUseDescriptor), ctx()) shouldBe Decision.Cancel
        }
    }

    given("ConfirmationRequired, any active factor is re-proven") {
        val acc = account(method("sms", "loa1"))
        val theCtx = ctx(account = acc)
        val state = DeleteAccountState.ConfirmationRequired(listOf("auth-sms"))

        then("deletes immediately - one proof, at any level, is always sufficient here") {
            val event = JourneyEvent.Completed(AuthSmsUseDescriptor, ToolOutcome.Completed.Authenticated(amr = listOf("sms")))
            strategy.decide(state, event, theCtx) shouldBe Decision.DeleteAccount(acc.accountId)
        }
    }

    given("onCancel") {
        then("always falls back to AUTHENTICATED - this intent only ever runs on an already-authenticated channel") {
            strategy.cancelledTo(DeleteAccountState.ConfirmPending) shouldBe ChannelState.AUTHENTICATED
            strategy.cancelledTo(DeleteAccountState.ConfirmationRequired(listOf("auth-sms"))) shouldBe ChannelState.AUTHENTICATED
        }
    }
})
