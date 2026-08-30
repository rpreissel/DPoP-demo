package com.example.dpop.orchestrator.journey.strategy

import com.example.dpop.auth_password.AuthPasswordUseDescriptor
import com.example.dpop.auth_sms.AuthSmsUseDescriptor
import com.example.dpop.orchestrator.journey.AuthIntent
import com.example.dpop.orchestrator.journey.Decision
import com.example.dpop.orchestrator.journey.Interpretation
import com.example.dpop.orchestrator.journey.JourneyEvent
import com.example.dpop.orchestrator.journey.state.StepUpState
import com.example.dpop.orchestrator.journey.strategy.StrategyTestFixtures.account
import com.example.dpop.orchestrator.journey.strategy.StrategyTestFixtures.ctx
import com.example.dpop.orchestrator.journey.strategy.StrategyTestFixtures.method
import com.example.dpop.orchestrator.policy.AuthEvidence
import com.example.dpop.orchestrator.session.ChannelState
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

    given("interpret") {
        val state = StepUpState.Start("loa2", "loa1")

        then("Authenticated is accepted as proof, never adopting a different account, always binding the device") {
            strategy.interpret(state, AuthSmsUseDescriptor, ToolOutcome.Completed.Authenticated(amr = listOf("sms"))) shouldBe
                Interpretation.AcceptProof(useOutcomeAccount = false, bindDevice = true)
        }

        then("Identified is not offered by this intent") {
            shouldThrow<IllegalStateException> {
                strategy.interpret(state, AuthSmsUseDescriptor, ToolOutcome.Completed.Identified(personId = 1L))
            }
        }

        then("Enrolled is not offered by this intent") {
            shouldThrow<IllegalStateException> {
                strategy.interpret(state, AuthSmsUseDescriptor, ToolOutcome.Completed.Enrolled(enrollmentRef = com.example.dpop.tool_spi.EnrollmentRef("sms", "ref")))
            }
        }
    }

    given("Start, freshly entered, an active method that can still help reach the target") {
        // sms alone caps at loa1, but it's unused this run - offering it "helps MFA" even before
        // it alone reaches loa2 (DefaultAuthPolicy.candidateTools' helpsMfa branch).
        val acc = account(method("sms", "loa2"))
        val theCtx = ctx(account = acc, evidence = AuthEvidence(emptyList(), emptySet()))
        val state = StepUpState.Start("loa2", "loa1")

        then("offers it via AuthChoice") {
            val decision = strategy.next(state, JourneyEvent.Started, theCtx)
            decision shouldBe Decision.Advance(StepUpState.AuthChoice("loa2", "loa1", listOf("auth-sms")))
        }
    }

    given("Start, the only active method already used this run, but re-identification could still help") {
        val acc = account(method("sms", "loa2"))
        val theCtx = ctx(account = acc, evidence = AuthEvidence(listOf("sms"), setOf(FactorType.POSSESSION)))
        val state = StepUpState.Start("loa2", "loa1")

        then("requires the shared RE_IDENTIFY sub-journey instead of aborting") {
            val decision = strategy.next(state, JourneyEvent.Started, theCtx)
            decision shouldBe Decision.RequireSubJourney(AuthIntent.RE_IDENTIFY, "loa2", resumeWith = StepUpState.Start("loa2", "loa1"))
        }
    }

    given("Start, nothing at all can close the gap (active method used, IDENT tools backend-disabled)") {
        val acc = account(method("sms", "loa2"))
        val theCtx = ctx(
            account = acc,
            evidence = AuthEvidence(listOf("sms"), setOf(FactorType.POSSESSION)),
            availableTools = StrategyTestFixtures.allToolIds - setOf("ident-fsc", "ident-eid")
        )
        val state = StepUpState.Start("loa2", "loa1")

        then("aborts with a reason, never a silent auto-pick") {
            val decision = strategy.next(state, JourneyEvent.Started, theCtx)
            decision.shouldBeInstanceOf<Decision.Abort>()
            (decision as Decision.Abort).reason shouldContain "nicht erreichbar"
        }
    }

    given("Start, resumed after a RE_IDENTIFY sub-journey (SubJourneyFinished)") {
        `when`("the fresh evidence already satisfies the target") {
            val acc = account(method("sms", "loa1"))
            val theCtx = ctx(account = acc, evidence = AuthEvidence(listOf("sms"), setOf(FactorType.POSSESSION)))
            val state = StepUpState.Start("loa1", "none")

            then("finishes directly instead of offering auth again") {
                val event = JourneyEvent.SubJourneyFinished(AuthIntent.RE_IDENTIFY, achievedAcr = "loa2")
                strategy.next(state, event, theCtx) shouldBe Decision.Finish
            }
        }

        `when`("it does not yet satisfy the target") {
            val acc = account(method("sms", "loa2"))
            val theCtx = ctx(account = acc, evidence = AuthEvidence(emptyList(), emptySet()))
            val state = StepUpState.Start("loa2", "loa1")

            then("falls through to offering auth candidates, same as a fresh Start") {
                val event = JourneyEvent.SubJourneyFinished(AuthIntent.RE_IDENTIFY, achievedAcr = null)
                strategy.next(state, event, theCtx) shouldBe Decision.Advance(StepUpState.AuthChoice("loa2", "loa1", listOf("auth-sms")))
            }
        }

        `when`("it was declined instead (SubJourneyCancelled)") {
            val acc = account(method("sms", "loa2"))
            val theCtx = ctx(account = acc, evidence = AuthEvidence(emptyList(), emptySet()))
            val state = StepUpState.Start("loa2", "loa1")

            then("gives up on its own rather than re-requesting the identical RE_IDENTIFY again") {
                val event = JourneyEvent.SubJourneyCancelled(AuthIntent.RE_IDENTIFY)
                strategy.next(state, event, theCtx) shouldBe Decision.Cancel
            }
        }
    }

    given("AuthChoice with more than one offered candidate") {
        val acc = account(method("sms", "loa2"), method("password", "loa2"))
        val theCtx = ctx(account = acc)
        val state = StepUpState.AuthChoice("loa2", "loa1", listOf("auth-sms", "auth-password"))

        `when`("one is abandoned, another remains") {
            then("advances, marking only that one declined") {
                strategy.next(state, JourneyEvent.Abandoned(AuthSmsUseDescriptor), theCtx) shouldBe
                    Decision.Advance(state.copy(declined = setOf("auth-sms"), active = null))
            }
        }
    }

    given("AuthChoice, the last offered candidate is abandoned") {
        val acc = account(method("sms", "loa2"))
        val state = StepUpState.AuthChoice("loa2", "loa1", listOf("auth-sms"))

        `when`("re-identification could still help") {
            val theCtx = ctx(account = acc, evidence = AuthEvidence(listOf("sms"), setOf(FactorType.POSSESSION)))
            then("requires the shared RE_IDENTIFY sub-journey") {
                strategy.next(state, JourneyEvent.Abandoned(AuthSmsUseDescriptor), theCtx) shouldBe
                    Decision.RequireSubJourney(AuthIntent.RE_IDENTIFY, "loa2", resumeWith = StepUpState.Start("loa2", "loa1"))
            }
        }

        `when`("re-identification cannot help either") {
            val theCtx = ctx(account = acc, evidence = AuthEvidence(listOf("sms", "fsc", "eid"), setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE)))
            then("cancels - giving up here is not an error") {
                strategy.next(state, JourneyEvent.Abandoned(AuthSmsUseDescriptor), theCtx) shouldBe Decision.Cancel
            }
        }
    }

    given("AuthChoice, a proof just completed") {
        // sms+password, both loa1 alone, both enrolled under loa2 - MFA-combine to loa2 (see
        // DefaultAuthPolicyTest for the underlying combination rule).
        val acc = account(method("sms", "loa2"), method("password", "loa2"))
        val theCtx = ctx(account = acc, evidence = AuthEvidence(listOf("sms", "password"), setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE)))
        val state = StepUpState.AuthChoice("loa2", "loa1", listOf("auth-sms", "auth-password"))

        then("finishes once the combination satisfies the target") {
            val event = JourneyEvent.Completed(AuthPasswordUseDescriptor, ToolOutcome.Completed.Authenticated(amr = listOf("password")))
            strategy.next(state, event, theCtx) shouldBe Decision.Finish
        }
    }

    given("onCancel") {
        then("always falls back to AUTHENTICATED - STEP_UP only ever runs on an already-authenticated channel") {
            strategy.onCancel(StepUpState.Start("loa2", "loa1")) shouldBe ChannelState.AUTHENTICATED
            strategy.onCancel(StepUpState.AuthChoice("loa2", "loa1", listOf("auth-sms"))) shouldBe ChannelState.AUTHENTICATED
        }
    }
})
