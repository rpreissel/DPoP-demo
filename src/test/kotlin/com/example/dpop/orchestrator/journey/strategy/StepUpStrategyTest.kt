package com.example.dpop.orchestrator.journey.strategy

import com.example.dpop.auth_password.AuthPasswordUseDescriptor
import com.example.dpop.auth_sms.AuthSmsUseDescriptor
import com.example.dpop.orchestrator.journey.Action
import com.example.dpop.orchestrator.journey.AuthIntent
import com.example.dpop.orchestrator.journey.JourneyEvent
import com.example.dpop.orchestrator.journey.Transition
import com.example.dpop.orchestrator.journey.state.StepUpState
import com.example.dpop.orchestrator.journey.strategy.StrategyTestFixtures.account
import com.example.dpop.orchestrator.journey.strategy.StrategyTestFixtures.ctx
import com.example.dpop.orchestrator.journey.strategy.StrategyTestFixtures.method
import com.example.dpop.orchestrator.policy.AuthEvidence
import com.example.dpop.orchestrator.journey.strategy.StrategyTestFixtures.evidence
import com.example.dpop.orchestrator.session.ChannelState
import com.example.dpop.tool_spi.EnrollmentRef
import com.example.dpop.tool_spi.FactorType
import com.example.dpop.tool_spi.ToolOutcome
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Pure unit coverage of [StepUpStrategy] - raising an already-authenticated channel's level
 * (docs/04-orchestrierung.md, "STEP_UP"). No Spring context, no HTTP.
 */
class StepUpStrategyTest : BehaviorSpec({

    val strategy = StepUpStrategy()

    given("the intent") {
        then("is STEP_UP") {
            strategy.intent shouldBe AuthIntent.STEP_UP
        }
    }

    given("initialStateForSubJourneyAcr") {
        then("seeds Start with exactly the given target/starting acr") {
            strategy.initialStateForSubJourneyAcr("loa2", "loa1") shouldBe StepUpState.Start("loa2", "loa1")
        }
    }

    given("AuthChoice, a completed tool") {
        val state = StepUpState.AuthChoice("loa2", "loa1", listOf("auth-sms"))

        then("Authenticated is accepted as proof, never adopting a different account, always binding the device") {
            val outcome = ToolOutcome.Completed.Authenticated(amr = listOf("sms"))
            val event = JourneyEvent.Completed(AuthSmsUseDescriptor, outcome)
            strategy.transition(state, event, ctx()) shouldBe
                Transition.Perform(Action.AcceptProof(AuthSmsUseDescriptor, outcome, useOutcomeAccount = false, bindDevice = true), resumeState = state)
        }

        then("Identified is not offered by this intent") {
            shouldThrow<IllegalStateException> {
                strategy.transition(state, JourneyEvent.Completed(AuthSmsUseDescriptor, ToolOutcome.Completed.Identified(personId = 1L)), ctx())
            }
        }

        then("Enrolled is not offered by this intent") {
            shouldThrow<IllegalStateException> {
                strategy.transition(state, JourneyEvent.Completed(AuthSmsUseDescriptor, ToolOutcome.Completed.Enrolled(enrollmentRef = EnrollmentRef("sms", "ref"))), ctx())
            }
        }
    }

    given("Start, freshly entered, an active method that can still help reach the target") {
        // sms alone caps at loa1, but it's unused this run - offering it "helps MFA" even before
        // it alone reaches loa2 (DefaultAuthPolicy.candidateTools' helpsMfa branch).
        val acc = account(method("sms", "loa2"))
        val theCtx = ctx(account = acc, evidence = AuthEvidence(emptyList()))
        val state = StepUpState.Start("loa2", "loa1")

        then("offers it via AuthChoice") {
            val transition = strategy.transition(state, JourneyEvent.Started, theCtx)
            transition shouldBe Transition.To(StepUpState.AuthChoice("loa2", "loa1", listOf("auth-sms")))
        }
    }

    given("Start, the only active method already used this run, but re-identification could still help") {
        val acc = account(method("sms", "loa2"))
        val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc))
        val state = StepUpState.Start("loa2", "loa1")

        then("requires the shared RE_IDENTIFY sub-journey instead of aborting") {
            val transition = strategy.transition(state, JourneyEvent.Started, theCtx)
            transition shouldBe Transition.RequireSubJourney(AuthIntent.RE_IDENTIFY, "loa2", resumeWith = StepUpState.Start("loa2", "loa1"))
        }
    }

    given("Start, nothing at all can close the gap (active method used, IDENT tools backend-disabled)") {
        val acc = account(method("sms", "loa2"))
        val theCtx = ctx(
            account = acc,
            evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc),
            availableTools = StrategyTestFixtures.allToolIds - setOf("ident-fsc", "ident-eid")
        )
        val state = StepUpState.Start("loa2", "loa1")

        then("aborts with a reason, never a silent auto-pick") {
            val transition = strategy.transition(state, JourneyEvent.Started, theCtx)
            transition.shouldBeInstanceOf<Transition.Abort>()
            (transition as Transition.Abort).reason shouldContain "nicht erreichbar"
        }
    }

    given("Start, resumed after a RE_IDENTIFY sub-journey (SubJourneyFinished)") {
        `when`("the fresh evidence already satisfies the target") {
            val acc = account(method("sms", "loa1"))
            val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc))
            val state = StepUpState.Start("loa1", "none")

            then("finishes directly instead of offering auth again") {
                val event = JourneyEvent.SubJourneyFinished(AuthIntent.RE_IDENTIFY, achievedAcr = "loa2")
                strategy.transition(state, event, theCtx) shouldBe Transition.Authenticated
            }
        }

        `when`("it does not yet satisfy the target") {
            val acc = account(method("sms", "loa2"))
            val theCtx = ctx(account = acc, evidence = AuthEvidence(emptyList()))
            val state = StepUpState.Start("loa2", "loa1")

            then("falls through to offering auth candidates, same as a fresh Start") {
                val event = JourneyEvent.SubJourneyFinished(AuthIntent.RE_IDENTIFY, achievedAcr = null)
                strategy.transition(state, event, theCtx) shouldBe Transition.To(StepUpState.AuthChoice("loa2", "loa1", listOf("auth-sms")))
            }
        }

        `when`("it was declined instead (SubJourneyCancelled)") {
            val acc = account(method("sms", "loa2"))
            val theCtx = ctx(account = acc, evidence = AuthEvidence(emptyList()))
            val state = StepUpState.Start("loa2", "loa1")

            then("gives up on its own rather than re-requesting the identical RE_IDENTIFY again") {
                val event = JourneyEvent.SubJourneyCancelled(AuthIntent.RE_IDENTIFY)
                strategy.transition(state, event, theCtx) shouldBe Transition.Cancel
            }
        }
    }

    given("AuthChoice with more than one offered candidate") {
        val acc = account(method("sms", "loa2"), method("password", "loa2"))
        val theCtx = ctx(account = acc)
        val state = StepUpState.AuthChoice("loa2", "loa1", listOf("auth-sms", "auth-password"))

        `when`("one is abandoned, another remains") {
            then("advances, marking only that one declined") {
                strategy.transition(state, JourneyEvent.Abandoned(AuthSmsUseDescriptor), theCtx) shouldBe
                    Transition.To(state.copy(declined = setOf("auth-sms"), active = null))
            }
        }
    }

    given("AuthChoice, the last offered candidate is abandoned") {
        val acc = account(method("sms", "loa2"))
        val state = StepUpState.AuthChoice("loa2", "loa1", listOf("auth-sms"))

        `when`("re-identification could still help") {
            val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc))
            then("requires the shared RE_IDENTIFY sub-journey") {
                strategy.transition(state, JourneyEvent.Abandoned(AuthSmsUseDescriptor), theCtx) shouldBe
                    Transition.RequireSubJourney(AuthIntent.RE_IDENTIFY, "loa2", resumeWith = StepUpState.Start("loa2", "loa1"))
            }
        }

        `when`("re-identification cannot help either") {
            val theCtx = ctx(account = acc, evidence = evidence(listOf("sms", "fsc", "eid"), setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE), account = acc))
            then("cancels - giving up here is not an error") {
                strategy.transition(state, JourneyEvent.Abandoned(AuthSmsUseDescriptor), theCtx) shouldBe Transition.Cancel
            }
        }
    }

    given("AuthChoice, a proof just completed, then resumed (ActionCompleted)") {
        // sms+password, both loa1 alone, both enrolled under loa2 - MFA-combine to loa2 (see
        // DefaultAuthPolicyTest for the underlying combination rule).
        val acc = account(method("sms", "loa2"), method("password", "loa2"))
        val theCtx = ctx(account = acc, evidence = evidence(listOf("sms", "password"), setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE), account = acc))
        val state = StepUpState.AuthChoice("loa2", "loa1", listOf("auth-sms", "auth-password"))

        then("performs AcceptProof, then finishes once the combination satisfies the target") {
            val outcome = ToolOutcome.Completed.Authenticated(amr = listOf("password"))
            val event = JourneyEvent.Completed(AuthPasswordUseDescriptor, outcome)
            strategy.transition(state, event, theCtx) shouldBe
                Transition.Perform(Action.AcceptProof(AuthPasswordUseDescriptor, outcome, useOutcomeAccount = false, bindDevice = true), resumeState = state)
            strategy.transition(state, JourneyEvent.ActionCompleted, theCtx) shouldBe Transition.Authenticated
        }
    }

    given("onCancel") {
        then("always falls back to AUTHENTICATED - STEP_UP only ever runs on an already-authenticated channel") {
            strategy.cancelledTo(StepUpState.Start("loa2", "loa1")) shouldBe ChannelState.AUTHENTICATED
            strategy.cancelledTo(StepUpState.AuthChoice("loa2", "loa1", listOf("auth-sms"))) shouldBe ChannelState.AUTHENTICATED
        }
    }
})
