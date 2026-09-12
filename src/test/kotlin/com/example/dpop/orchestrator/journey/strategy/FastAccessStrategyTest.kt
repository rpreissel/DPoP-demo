package com.example.dpop.orchestrator.journey.strategy

import com.example.dpop.auth_device.AuthDeviceDescriptor
import com.example.dpop.auth_sms.AuthSmsUseDescriptor
import com.example.dpop.id_fsc.IdentFscDescriptor
import com.example.dpop.orchestrator.journey.Action
import com.example.dpop.orchestrator.journey.AuthIntent
import com.example.dpop.orchestrator.journey.JourneyEvent
import com.example.dpop.orchestrator.journey.Transition
import com.example.dpop.orchestrator.journey.state.AuthChoice
import com.example.dpop.orchestrator.journey.state.Enrolling
import com.example.dpop.orchestrator.journey.state.FastAccessState
import com.example.dpop.orchestrator.journey.state.ReIdentifyState
import com.example.dpop.orchestrator.journey.state.RegisterState
import com.example.dpop.orchestrator.journey.strategy.StrategyTestFixtures.account
import com.example.dpop.orchestrator.journey.strategy.StrategyTestFixtures.ctx
import com.example.dpop.orchestrator.journey.strategy.StrategyTestFixtures.deviceDetails
import com.example.dpop.orchestrator.journey.strategy.StrategyTestFixtures.method
import com.example.dpop.orchestrator.journey.strategy.StrategyTestFixtures.evidence
import com.example.dpop.orchestrator.session.ChannelState
import com.example.dpop.tool_spi.EnrollmentRef
import com.example.dpop.tool_spi.FactorType
import com.example.dpop.tool_spi.ToolOutcome
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Pure unit coverage of [FastAccessStrategy] - the fallback chain into a login, plus [Enrolling]
 * for the one case it reaches inline without ever identifying anyone (docs/04-orchestrierung.md,
 * "FAST_ACCESS"). No Spring context, no HTTP.
 *
 * Everything identification-related ([RegisterState.Identifying], [RegisterState.ConfirmingEmail],
 * [RegisterState.PasswordObligation]) is [RegisterStrategy]'s own journey now, reached only via
 * [Transition.RequireSubJourney] - see [RegisterStrategyTest] for that coverage.
 *
 * A completed tool always turns into a [Transition.Perform] first, resumed with
 * [JourneyEvent.ActionCompleted] against the SAME state once that action has run
 * (docs/ideen/journey-strategie-vereinheitlichung.md #2) - tests that used to assert the
 * post-effect outcome directly now assert both steps: the `Perform` naming the [Action], then the
 * `ActionCompleted` follow-up against a context already reflecting what that action established.
 */
class FastAccessStrategyTest : BehaviorSpec({

    val strategy = FastAccessStrategy()

    given("the intent") {
        then("is FAST_ACCESS") {
            strategy.intent shouldBe AuthIntent.FAST_ACCESS
        }
    }

    given("a completed tool, interpreted the same regardless of which offering state routed it here") {
        val state = AuthChoice(listOf("auth-sms"))

        then("Identified always finds-or-creates the account - brand new or found again by KVNR alike") {
            val outcome = ToolOutcome.Completed.Identified(personId = 1L)
            val event = JourneyEvent.Completed(IdentFscDescriptor, outcome)
            strategy.transition(state, event, ctx()) shouldBe
                Transition.Perform(Action.AdoptIdentity(IdentFscDescriptor, outcome), resumeState = state)
        }

        then("Enrolled binds the device - a fresh credential on this device is worth remembering") {
            val outcome = ToolOutcome.Completed.Enrolled(enrollmentRef = EnrollmentRef("sms", "ref"))
            val event = JourneyEvent.Completed(AuthSmsUseDescriptor, outcome)
            strategy.transition(state, event, ctx()) shouldBe
                Transition.Perform(Action.AdoptCredential(AuthSmsUseDescriptor, outcome, bindDevice = true), resumeState = state)
        }

        then("Authenticated is accepted as proof, never adopting a different account, always binding the device") {
            val outcome = ToolOutcome.Completed.Authenticated(amr = listOf("sms"))
            val event = JourneyEvent.Completed(AuthSmsUseDescriptor, outcome)
            strategy.transition(state, event, ctx()) shouldBe
                Transition.Perform(Action.AcceptProof(AuthSmsUseDescriptor, outcome, useOutcomeAccount = false, bindDevice = true), resumeState = state)
        }
    }

    given("Start, no account known yet (unrecognized device)") {
        then("hands off to REGISTER's own journey to identify") {
            strategy.transition(FastAccessState.Start, JourneyEvent.Started, ctx(account = null)) shouldBe
                Transition.RequireSubJourney(AuthIntent.REGISTER, seedWith = RegisterState.Start, resumeWith = FastAccessState.Start)
        }
    }

    given("Start, account known via a linked device credential") {
        val acc = account(method("device", "loa2", details = deviceDetails()))
        then("suggests exactly that device credential, not a generic choice") {
            strategy.transition(FastAccessState.Start, JourneyEvent.Started, ctx(account = acc)) shouldBe
                Transition.To(FastAccessState.PreferredAuth("auth-device"))
        }
    }

    given("Start, account known but no preferred device - other methods available") {
        val acc = account(method("sms", "loa2"))
        then("offers them via AuthChoice") {
            strategy.transition(FastAccessState.Start, JourneyEvent.Started, ctx(account = acc)) shouldBe
                Transition.To(AuthChoice(listOf("auth-sms")))
        }
    }

    given("Start, account known but has no active methods at all") {
        val acc = account()
        then("hands off to REGISTER's own journey rather than a dead end") {
            strategy.transition(FastAccessState.Start, JourneyEvent.Started, ctx(account = acc)) shouldBe
                Transition.RequireSubJourney(AuthIntent.REGISTER, seedWith = RegisterState.Start, resumeWith = FastAccessState.Start)
        }
    }

    given("Start, resumed after a sub-journey finished (RE_IDENTIFY or REGISTER alike)") {
        val acc = account(method("sms", "loa1"))
        val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc), acrFloor = "loa1")
        then("re-checks satisfaction via afterProof instead of re-running firstOffer") {
            val event = JourneyEvent.SubJourneyFinished(AuthIntent.RE_IDENTIFY, achievedAcr = "loa2")
            strategy.transition(FastAccessState.Start, event, theCtx) shouldBe Transition.Authenticated
        }
    }

    given("Start, declined instead (SubJourneyCancelled)") {
        then("gives up on its own rather than re-requesting the identical sub-journey again") {
            val event = JourneyEvent.SubJourneyCancelled(AuthIntent.RE_IDENTIFY)
            strategy.transition(FastAccessState.Start, event, ctx()) shouldBe Transition.Cancel
        }
    }

    given("PreferredAuth, declined") {
        val acc = account(method("device", "loa2", details = deviceDetails()), method("sms", "loa2"))
        then("falls back to the account's other methods") {
            strategy.transition(FastAccessState.PreferredAuth("auth-device"), JourneyEvent.Abandoned(AuthDeviceDescriptor), ctx(account = acc)) shouldBe
                Transition.To(AuthChoice(listOf("auth-sms"), declined = emptySet()))
        }
    }

    given("PreferredAuth, a proof just completed and it already satisfies the floor") {
        val acc = account(method("device", "loa2", details = deviceDetails()))
        val theCtx = ctx(account = acc, evidence = evidence(listOf("device"), setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE, FactorType.INHERENCE), account = acc), acrFloor = "loa2")
        val state = FastAccessState.PreferredAuth("auth-device")
        then("accepts the proof, then finishes once resumed") {
            val outcome = ToolOutcome.Completed.Authenticated(amr = listOf("device"))
            val event = JourneyEvent.Completed(AuthDeviceDescriptor, outcome)
            strategy.transition(state, event, theCtx) shouldBe
                Transition.Perform(Action.AcceptProof(AuthDeviceDescriptor, outcome, useOutcomeAccount = false, bindDevice = true), resumeState = state)
            strategy.transition(state, JourneyEvent.ActionCompleted, theCtx) shouldBe Transition.Authenticated
        }
    }

    given("AuthChoice, more than one offered candidate") {
        val state = AuthChoice(listOf("auth-sms", "auth-password"))
        val acc = account(method("sms", "loa2"), method("password", "loa2"))
        then("abandoning one keeps the run in AuthChoice with the rest still offered") {
            strategy.transition(state, JourneyEvent.Abandoned(AuthSmsUseDescriptor), ctx(account = acc)) shouldBe
                Transition.To(state.copy(declined = setOf("auth-sms"), active = null))
        }
    }

    given("AuthChoice, the last offered candidate is abandoned") {
        val acc = account(method("sms", "loa2"))
        then("hands off to REGISTER's own journey, same as an account with nothing usable at all") {
            strategy.transition(AuthChoice(listOf("auth-sms")), JourneyEvent.Abandoned(AuthSmsUseDescriptor), ctx(account = acc)) shouldBe
                Transition.RequireSubJourney(AuthIntent.REGISTER, seedWith = RegisterState.Start, resumeWith = FastAccessState.Start)
        }
    }

    given("Enrolling, reached inline after a proof left the floor unsatisfied with nothing else to try") {
        `when`("abandoned") {
            val state = Enrolling(listOf("enroll-sms"), emailObligation = false)
            then("re-offers the same full choice, the tool just backed out of included") {
                strategy.transition(state, JourneyEvent.Abandoned(AuthSmsUseDescriptor), ctx()) shouldBe Transition.To(state.withActive(null))
            }
        }

        `when`("a method was just enrolled and the floor is now reached") {
            val acc = account(method("sms", "loa1"))
            val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc), acrFloor = "loa1")
            val state = Enrolling(listOf("enroll-sms"), emailObligation = false)
            then("adopts the credential, then finishes directly - FAST_ACCESS never carries an email obligation") {
                val outcome = ToolOutcome.Completed.Enrolled(enrollmentRef = EnrollmentRef("sms", "ref"))
                val event = JourneyEvent.Completed(AuthSmsUseDescriptor, outcome)
                strategy.transition(state, event, theCtx) shouldBe
                    Transition.Perform(Action.AdoptCredential(AuthSmsUseDescriptor, outcome, bindDevice = true), resumeState = state)
                strategy.transition(state, JourneyEvent.ActionCompleted, theCtx) shouldBe Transition.Authenticated
            }
        }
    }

    given("afterProof's own dead end: no enrollment tool left at all (all backend-disabled)") {
        val acc = account(method("sms", "loa2"))
        val onlyAuthTools = setOf("auth-sms")
        val state = AuthChoice(listOf("auth-sms"))

        `when`("re-identification could still close the gap") {
            val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc), acrFloor = "loa2", availableTools = onlyAuthTools + setOf("ident-fsc", "ident-eid"))
            then("accepts the proof, then requires the shared RE_IDENTIFY sub-journey instead of aborting") {
                val outcome = ToolOutcome.Completed.Authenticated(amr = listOf("sms"))
                val event = JourneyEvent.Completed(AuthSmsUseDescriptor, outcome)
                strategy.transition(state, event, theCtx) shouldBe
                    Transition.Perform(Action.AcceptProof(AuthSmsUseDescriptor, outcome, useOutcomeAccount = false, bindDevice = true), resumeState = state)
                strategy.transition(state, JourneyEvent.ActionCompleted, theCtx) shouldBe
                    Transition.RequireSubJourney(
                        AuthIntent.RE_IDENTIFY,
                        seedWith = ReIdentifyState.forSubJourney("loa2", "loa1"),
                        resumeWith = FastAccessState.Start
                    )
            }
        }

        `when`("nothing can help at all") {
            val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc), acrFloor = "loa2", availableTools = onlyAuthTools)
            then("aborts with a reason") {
                strategy.transition(state, JourneyEvent.ActionCompleted, theCtx).shouldBeInstanceOf<Transition.Abort>()
            }
        }
    }

    given("onCancel") {
        then("always falls back to ANONYMOUS") {
            strategy.cancelledTo(FastAccessState.Start) shouldBe ChannelState.ANONYMOUS
            strategy.cancelledTo(FastAccessState.PreferredAuth("auth-device")) shouldBe ChannelState.ANONYMOUS
        }
    }
})
