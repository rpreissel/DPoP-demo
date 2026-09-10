package com.example.dpop.orchestrator.journey.strategy

import com.example.dpop.auth_sms.AuthSmsUseDescriptor
import com.example.dpop.id_fsc.IdentFscDescriptor
import com.example.dpop.orchestrator.journey.Action
import com.example.dpop.orchestrator.journey.AuthIntent
import com.example.dpop.orchestrator.journey.JourneyEvent
import com.example.dpop.orchestrator.journey.Transition
import com.example.dpop.orchestrator.journey.state.ManageAuthMethodsState
import com.example.dpop.orchestrator.journey.strategy.StrategyTestFixtures.account
import com.example.dpop.orchestrator.journey.strategy.StrategyTestFixtures.ctx
import com.example.dpop.orchestrator.journey.strategy.StrategyTestFixtures.method
import com.example.dpop.orchestrator.journey.strategy.StrategyTestFixtures.evidence
import com.example.dpop.orchestrator.session.ChannelState
import com.example.dpop.tool_spi.EnrollmentRef
import com.example.dpop.tool_spi.FactorType
import com.example.dpop.tool_spi.ToolOutcome
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Pure unit coverage of [ManageAuthMethodsStrategy] - add/remove authentication methods on an
 * already-authenticated channel (docs/04-orchestrierung.md, "MANAGE_AUTH_METHODS"). No Spring
 * context, no HTTP.
 */
class ManageAuthMethodsStrategyTest : BehaviorSpec({

    val strategy = ManageAuthMethodsStrategy()

    given("the intent") {
        then("is MANAGE_AUTH_METHODS") {
            strategy.intent shouldBe AuthIntent.MANAGE_AUTH_METHODS
        }
    }

    given("initialState") {
        then("is AddRequested") {
            strategy.initialState(ctx()) shouldBe ManageAuthMethodsState.AddRequested
        }
    }

    given("Enrolling, a completed tool") {
        val state = ManageAuthMethodsState.Enrolling(listOf("enroll-sms"))

        then("Enrolled binds the device - it's already known, so this is a harmless no-op that keeps it reachable") {
            val outcome = ToolOutcome.Completed.Enrolled(enrollmentRef = EnrollmentRef("sms", "ref"))
            val event = JourneyEvent.Completed(AuthSmsUseDescriptor, outcome)
            strategy.transition(state, event, ctx()) shouldBe
                Transition.Perform(Action.AdoptCredential(AuthSmsUseDescriptor, outcome, bindDevice = true), resumeState = state)
        }

        then("Identified is not offered by this intent") {
            shouldThrow<IllegalStateException> {
                strategy.transition(state, JourneyEvent.Completed(IdentFscDescriptor, ToolOutcome.Completed.Identified(personId = 1L)), ctx())
            }
        }

        then("Authenticated is not offered by this intent") {
            shouldThrow<IllegalStateException> {
                strategy.transition(state, JourneyEvent.Completed(AuthSmsUseDescriptor, ToolOutcome.Completed.Authenticated(amr = listOf("sms"))), ctx())
            }
        }
    }

    given("AddRequested, the session does not yet carry loa2") {
        val acc = account(method("sms", "loa1"))
        val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc))
        then("parks the wish and demands a step-up first, without losing it") {
            strategy.transition(ManageAuthMethodsState.AddRequested, JourneyEvent.Started, theCtx) shouldBe
                Transition.RequireSubJourney(AuthIntent.STEP_UP, "loa2", resumeWith = ManageAuthMethodsState.AddRequested)
        }
    }

    given("AddRequested, the session already carries loa2") {
        val acc = account(method("sms", "loa1"))
        val theCtx = ctx(account = acc, evidence = evidence(listOf("fsc"), setOf(FactorType.POSSESSION), account = acc), acrFloor = "loa1")

        then("offers enrollment candidates directly") {
            val transition = strategy.transition(ManageAuthMethodsState.AddRequested, JourneyEvent.Started, theCtx)
            transition.shouldBeInstanceOf<Transition.To>()
        }
    }

    given("AddRequested, loa2 satisfied but nothing left to enroll") {
        val acc = account(
            method("sms", "loa2"), method("password", "loa2"), method("email", "loa2"), method("device", "loa2"), method("qr", "loa2")
        )
        val theCtx = ctx(account = acc, evidence = evidence(listOf("fsc"), setOf(FactorType.POSSESSION), account = acc), acrFloor = "loa1")

        then("finishes - not an error, just nothing more to add (device stays offered since it allows multiple instances, so this really only fires once every singleton method is active)") {
            // device (allowsMultipleInstances) is deliberately still offered even with one active
            // instance, so this case is only reachable by ALSO backend-disabling it.
            val transition = strategy.transition(ManageAuthMethodsState.AddRequested, JourneyEvent.Started, theCtx.copy(availableTools = theCtx.availableTools - "enroll-device"))
            transition shouldBe Transition.Authenticated
        }
    }

    given("AddRequested, resumed after the gate's own STEP_UP was declined (SubJourneyCancelled)") {
        val acc = account(method("sms", "loa1"))
        val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc))
        then("gives up on the wish rather than re-requesting the identical STEP_UP again") {
            strategy.transition(ManageAuthMethodsState.AddRequested, JourneyEvent.SubJourneyCancelled(AuthIntent.STEP_UP), theCtx) shouldBe
                Transition.Cancel
        }
    }

    given("RemoveRequested, the session does not yet carry loa2") {
        val acc = account(method("sms", "loa1"))
        val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc))
        val state = ManageAuthMethodsState.RemoveRequested("sms-instance")

        then("parks the wish and demands a step-up first") {
            strategy.transition(state, JourneyEvent.Started, theCtx) shouldBe
                Transition.RequireSubJourney(AuthIntent.STEP_UP, "loa2", resumeWith = state)
        }
    }

    given("RemoveRequested, the session already carries loa2") {
        val acc = account(method("sms", "loa2"))
        val theCtx = ctx(account = acc, evidence = evidence(listOf("fsc"), setOf(FactorType.POSSESSION), account = acc), acrFloor = "loa1")
        val state = ManageAuthMethodsState.RemoveRequested("sms-instance")

        then("removes the method directly, then finishes once resumed - the machine, not this strategy, rejects self-lockout") {
            strategy.transition(state, JourneyEvent.Started, theCtx) shouldBe
                Transition.Perform(Action.Remove("sms-instance"), resumeState = state)
            strategy.transition(state, JourneyEvent.ActionCompleted, theCtx) shouldBe Transition.Authenticated
        }
    }

    given("RemoveRequested, resumed after the gate's own STEP_UP was declined (SubJourneyCancelled)") {
        val acc = account(method("sms", "loa1"))
        val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc))
        val state = ManageAuthMethodsState.RemoveRequested("sms-instance")
        then("gives up on the wish rather than re-requesting the identical STEP_UP again") {
            strategy.transition(state, JourneyEvent.SubJourneyCancelled(AuthIntent.STEP_UP), theCtx) shouldBe Transition.Cancel
        }
    }

    given("Enrolling") {
        val state = ManageAuthMethodsState.Enrolling(listOf("enroll-sms", "enroll-password"))

        `when`("a tool is abandoned") {
            then("stays in Enrolling with the full choice back - not a decline, just picking differently") {
                strategy.transition(state, JourneyEvent.Abandoned(AuthSmsUseDescriptor), ctx()) shouldBe
                    Transition.To(state.copy(active = null))
            }
        }

        `when`("a method is enrolled") {
            then("adopts the credential, then finishes once resumed - one successful enrollment is always enough here") {
                val outcome = ToolOutcome.Completed.Enrolled(enrollmentRef = EnrollmentRef("sms", "ref"))
                val event = JourneyEvent.Completed(AuthSmsUseDescriptor, outcome)
                strategy.transition(state, event, ctx()) shouldBe
                    Transition.Perform(Action.AdoptCredential(AuthSmsUseDescriptor, outcome, bindDevice = true), resumeState = state)
                strategy.transition(state, JourneyEvent.ActionCompleted, ctx()) shouldBe Transition.Authenticated
            }
        }
    }

    given("onCancel") {
        then("always falls back to AUTHENTICATED - this intent only ever runs on an already-authenticated channel") {
            strategy.cancelledTo(ManageAuthMethodsState.AddRequested) shouldBe ChannelState.AUTHENTICATED
            strategy.cancelledTo(ManageAuthMethodsState.RemoveRequested("sms-instance")) shouldBe ChannelState.AUTHENTICATED
        }
    }
})
