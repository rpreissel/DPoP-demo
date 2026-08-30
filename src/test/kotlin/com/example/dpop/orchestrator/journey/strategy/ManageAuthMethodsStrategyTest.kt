package com.example.dpop.orchestrator.journey.strategy

import com.example.dpop.auth_sms.AuthSmsUseDescriptor
import com.example.dpop.id_fsc.IdentFscDescriptor
import com.example.dpop.orchestrator.journey.AuthIntent
import com.example.dpop.orchestrator.journey.Decision
import com.example.dpop.orchestrator.journey.Interpretation
import com.example.dpop.orchestrator.journey.JourneyEvent
import com.example.dpop.orchestrator.journey.state.ManageAuthMethodsState
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

    given("interpret") {
        then("Enrolled binds the device - it's already known, so this is a harmless no-op that keeps it reachable") {
            strategy.interpret(ManageAuthMethodsState.AddRequested, AuthSmsUseDescriptor, ToolOutcome.Completed.Enrolled(enrollmentRef = com.example.dpop.tool_spi.EnrollmentRef("sms", "ref"))) shouldBe
                Interpretation.AdoptCredential(bindDevice = true)
        }

        then("Identified is not offered by this intent") {
            shouldThrow<IllegalStateException> {
                strategy.interpret(ManageAuthMethodsState.AddRequested, IdentFscDescriptor, ToolOutcome.Completed.Identified(personId = 1L))
            }
        }

        then("Authenticated is not offered by this intent") {
            shouldThrow<IllegalStateException> {
                strategy.interpret(ManageAuthMethodsState.AddRequested, AuthSmsUseDescriptor, ToolOutcome.Completed.Authenticated(amr = listOf("sms")))
            }
        }
    }

    given("AddRequested, the session does not yet carry loa2") {
        val acc = account(method("sms", "loa1"))
        val theCtx = ctx(account = acc, evidence = AuthEvidence(listOf("sms"), setOf(FactorType.POSSESSION)))
        then("parks the wish and demands a step-up first, without losing it") {
            strategy.next(ManageAuthMethodsState.AddRequested, JourneyEvent.Started, theCtx) shouldBe
                Decision.RequireSubJourney(AuthIntent.STEP_UP, "loa2", resumeWith = ManageAuthMethodsState.AddRequested)
        }
    }

    given("AddRequested, the session already carries loa2") {
        val acc = account(method("sms", "loa1"))
        val theCtx = ctx(account = acc, evidence = AuthEvidence(listOf("fsc"), setOf(FactorType.POSSESSION)), acrFloor = "loa1")

        then("offers enrollment candidates directly") {
            val decision = strategy.next(ManageAuthMethodsState.AddRequested, JourneyEvent.Started, theCtx)
            decision.shouldBeInstanceOf<Decision.Advance>()
        }
    }

    given("AddRequested, loa2 satisfied but nothing left to enroll") {
        val acc = account(
            method("sms", "loa2"), method("password", "loa2"), method("email", "loa2"), method("device", "loa2")
        )
        val theCtx = ctx(account = acc, evidence = AuthEvidence(listOf("fsc"), setOf(FactorType.POSSESSION)), acrFloor = "loa1")

        then("finishes - not an error, just nothing more to add (device stays offered since it allows multiple instances, so this really only fires once every singleton method is active)") {
            // device (allowsMultipleInstances) is deliberately still offered even with one active
            // instance, so this case is only reachable by ALSO backend-disabling it.
            val decision = strategy.next(ManageAuthMethodsState.AddRequested, JourneyEvent.Started, theCtx.copy(availableTools = theCtx.availableTools - "enroll-device"))
            decision shouldBe Decision.Finish
        }
    }

    given("RemoveRequested, the session does not yet carry loa2") {
        val acc = account(method("sms", "loa1"))
        val theCtx = ctx(account = acc, evidence = AuthEvidence(listOf("sms"), setOf(FactorType.POSSESSION)))
        val state = ManageAuthMethodsState.RemoveRequested("sms-instance")

        then("parks the wish and demands a step-up first") {
            strategy.next(state, JourneyEvent.Started, theCtx) shouldBe
                Decision.RequireSubJourney(AuthIntent.STEP_UP, "loa2", resumeWith = state)
        }
    }

    given("RemoveRequested, the session already carries loa2") {
        val acc = account(method("sms", "loa2"))
        val theCtx = ctx(account = acc, evidence = AuthEvidence(listOf("fsc"), setOf(FactorType.POSSESSION)), acrFloor = "loa1")
        val state = ManageAuthMethodsState.RemoveRequested("sms-instance")

        then("removes the method directly - the machine, not this strategy, rejects self-lockout") {
            strategy.next(state, JourneyEvent.Started, theCtx) shouldBe Decision.Remove("sms-instance")
        }
    }

    given("Enrolling") {
        val state = ManageAuthMethodsState.Enrolling(listOf("enroll-sms", "enroll-password"))

        `when`("a tool is abandoned") {
            then("stays in Enrolling with the full choice back - not a decline, just picking differently") {
                strategy.next(state, JourneyEvent.Abandoned(AuthSmsUseDescriptor), ctx()) shouldBe
                    Decision.Advance(state.copy(active = null))
            }
        }

        `when`("a method is enrolled") {
            then("finishes - one successful enrollment is always enough here") {
                val event = JourneyEvent.Completed(AuthSmsUseDescriptor, ToolOutcome.Completed.Enrolled(enrollmentRef = com.example.dpop.tool_spi.EnrollmentRef("sms", "ref")))
                strategy.next(state, event, ctx()) shouldBe Decision.Finish
            }
        }
    }

    given("onCancel") {
        then("always falls back to AUTHENTICATED - this intent only ever runs on an already-authenticated channel") {
            strategy.onCancel(ManageAuthMethodsState.AddRequested) shouldBe ChannelState.AUTHENTICATED
            strategy.onCancel(ManageAuthMethodsState.RemoveRequested("sms-instance")) shouldBe ChannelState.AUTHENTICATED
        }
    }
})
