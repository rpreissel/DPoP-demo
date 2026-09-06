package com.example.dpop.orchestrator.journey.strategy

import com.example.dpop.auth_sms.AuthSmsUseDescriptor
import com.example.dpop.orchestrator.journey.Action
import com.example.dpop.orchestrator.journey.AuthIntent
import com.example.dpop.orchestrator.journey.JourneyEvent
import com.example.dpop.orchestrator.journey.Transition
import com.example.dpop.orchestrator.journey.state.DeleteAccountState
import com.example.dpop.orchestrator.journey.strategy.StrategyTestFixtures.account
import com.example.dpop.orchestrator.journey.strategy.StrategyTestFixtures.ctx
import com.example.dpop.orchestrator.journey.strategy.StrategyTestFixtures.method
import com.example.dpop.orchestrator.journey.strategy.StrategyTestFixtures.evidence
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

    given("ConfirmationRequired, an outcome this intent never offers") {
        val state = DeleteAccountState.ConfirmationRequired(listOf("ident-fsc"))
        then("Identified fails loudly rather than silently deleting") {
            shouldThrow<IllegalStateException> {
                strategy.transition(
                    state,
                    JourneyEvent.Completed(com.example.dpop.id_fsc.IdentFscDescriptor, ToolOutcome.Completed.Identified(personId = 1L)),
                    ctx()
                )
            }
        }
        then("Enrolled fails loudly rather than silently deleting") {
            shouldThrow<IllegalStateException> {
                strategy.transition(
                    state,
                    JourneyEvent.Completed(AuthSmsUseDescriptor, ToolOutcome.Completed.Enrolled(enrollmentRef = com.example.dpop.tool_spi.EnrollmentRef("sms", "ref"))),
                    ctx()
                )
            }
        }
    }

    given("ConfirmPending, just started") {
        then("unconditionally re-presents the confirmation prompt") {
            strategy.transition(DeleteAccountState.ConfirmPending, JourneyEvent.Started, ctx()) shouldBe Transition.To(DeleteAccountState.ConfirmPending)
        }
    }

    given("ConfirmPending, declined") {
        then("cancels - no gate is ever evaluated before an explicit yes") {
            strategy.transition(DeleteAccountState.ConfirmPending, JourneyEvent.Answered("decline"), ctx()) shouldBe Transition.Cancel
        }
    }

    given("ConfirmPending, an unrecognized answer") {
        then("fails loudly rather than guessing") {
            shouldThrow<IllegalStateException> { strategy.transition(DeleteAccountState.ConfirmPending, JourneyEvent.Answered("maybe"), ctx()) }
        }
    }

    given("ConfirmPending, accepted") {
        `when`("the session does not yet carry loa2") {
            val acc = account(method("sms", "loa1"))
            val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc))
            then("the loa2 gate parks the wish and demands a step-up first - only NOW, never before accepting") {
                strategy.transition(DeleteAccountState.ConfirmPending, JourneyEvent.Answered("accept"), theCtx) shouldBe
                    Transition.RequireSubJourney(AuthIntent.STEP_UP, "loa2", resumeWith = DeleteAccountState.ConfirmPending)
            }
        }

        `when`("the session already carries loa2") {
            // device is the only tool whose own maxAcr reaches loa2 alone (sms/password/email cap
            // at loa1) - so this is the only single-method way to seed "already at loa2" evidence.
            val acc = account(method("device", "loa2", details = StrategyTestFixtures.deviceDetails()))
            val theCtx = ctx(account = acc, evidence = evidence(listOf("device"), setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE, FactorType.INHERENCE), account = acc), acrFloor = "loa1")
            then("still demands a fresh re-confirmation of any active factor - unlike STEP_UP, evidence of unknown age is never enough on its own") {
                val transition = strategy.transition(DeleteAccountState.ConfirmPending, JourneyEvent.Answered("accept"), theCtx)
                transition.shouldBeInstanceOf<Transition.To>()
                val to = (transition as Transition.To).state
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
                strategy.transition(DeleteAccountState.ConfirmPending, event, theCtx) shouldBe
                    Transition.Perform(Action.DeleteAccount(acc.accountId), resumeState = DeleteAccountState.ConfirmPending)
            }
        }

        `when`("it was a STEP_UP that fell short of loa2") {
            val acc = account(method("sms", "loa2"))
            val theCtx = ctx(account = acc)
            then("does not delete and does not fall back to a lesser reconfirmation either - that would let a session stuck below loa2 delete via the very factor that couldn't reach it") {
                val event = JourneyEvent.SubJourneyFinished(AuthIntent.STEP_UP, achievedAcr = "loa1")
                strategy.transition(DeleteAccountState.ConfirmPending, event, theCtx) shouldBe Transition.Cancel
            }
        }

        `when`("it was a different sub-journey entirely - never assumed to be the gate's own") {
            val acc = account(method("sms", "loa2"))
            val theCtx = ctx(account = acc)
            then("does not delete") {
                val event = JourneyEvent.SubJourneyFinished(AuthIntent.RE_IDENTIFY, achievedAcr = "loa3")
                strategy.transition(DeleteAccountState.ConfirmPending, event, theCtx) shouldBe Transition.Cancel
            }
        }

        `when`("the gate's own STEP_UP was declined instead (SubJourneyCancelled)") {
            val acc = account(method("sms", "loa2"))
            val theCtx = ctx(account = acc)
            then("does not delete - same as falling short, not a lesser fallback") {
                val event = JourneyEvent.SubJourneyCancelled(AuthIntent.STEP_UP)
                strategy.transition(DeleteAccountState.ConfirmPending, event, theCtx) shouldBe Transition.Cancel
            }
        }
    }

    given("ConfirmPending, the gate's own step-up delete just ran (ActionCompleted)") {
        then("ends the channel for good") {
            strategy.transition(DeleteAccountState.ConfirmPending, JourneyEvent.ActionCompleted, ctx()) shouldBe Transition.Logout
        }
    }

    given("ConfirmationRequired, more than one offered candidate") {
        val state = DeleteAccountState.ConfirmationRequired(listOf("auth-sms", "auth-password"))
        then("abandoning one keeps the choice among the rest") {
            strategy.transition(state, JourneyEvent.Abandoned(AuthSmsUseDescriptor), ctx()) shouldBe
                Transition.To(state.copy(declined = setOf("auth-sms"), active = null))
        }
    }

    given("ConfirmationRequired, the last offered candidate is abandoned") {
        val state = DeleteAccountState.ConfirmationRequired(listOf("auth-sms"))
        then("cancels - the account is never deleted just because every option was declined") {
            strategy.transition(state, JourneyEvent.Abandoned(AuthSmsUseDescriptor), ctx()) shouldBe Transition.Cancel
        }
    }

    given("ConfirmationRequired, any active factor is re-proven") {
        val acc = account(method("sms", "loa1"))
        val theCtx = ctx(account = acc)
        val state = DeleteAccountState.ConfirmationRequired(listOf("auth-sms"))

        then("goes straight to deleting - one proof, at any level, is always sufficient here, and is never itself recorded as MethodEvidence") {
            val event = JourneyEvent.Completed(AuthSmsUseDescriptor, ToolOutcome.Completed.Authenticated(amr = listOf("sms")))
            strategy.transition(state, event, theCtx) shouldBe Transition.Perform(Action.DeleteAccount(acc.accountId), resumeState = state)
        }
    }

    given("ConfirmationRequired, the delete just ran (ActionCompleted)") {
        then("ends the channel for good") {
            val state = DeleteAccountState.ConfirmationRequired(listOf("auth-sms"))
            strategy.transition(state, JourneyEvent.ActionCompleted, ctx()) shouldBe Transition.Logout
        }
    }

    given("onCancel") {
        then("always falls back to AUTHENTICATED - this intent only ever runs on an already-authenticated channel") {
            strategy.cancelledTo(DeleteAccountState.ConfirmPending) shouldBe ChannelState.AUTHENTICATED
            strategy.cancelledTo(DeleteAccountState.ConfirmationRequired(listOf("auth-sms"))) shouldBe ChannelState.AUTHENTICATED
        }
    }
})
