package com.example.dpop.orchestrator.domain.journey.strategy

import com.example.dpop.orchestrator.domain.journey.strategy.ManageAuthMethodsStrategy
import com.example.dpop.auth_sms.AuthSmsDescriptor
import com.example.dpop.id_fsc.IdentFscDescriptor
import com.example.dpop.orchestrator.domain.journey.Action
import com.example.dpop.orchestrator.domain.AuthIntent
import com.example.dpop.orchestrator.domain.journey.JourneyEvent
import com.example.dpop.orchestrator.domain.journey.Transition
import com.example.dpop.orchestrator.domain.journey.state.Offer
import com.example.dpop.orchestrator.domain.journey.state.ManageAuthMethodsState
import com.example.dpop.orchestrator.domain.journey.state.StepUpState
import com.example.dpop.orchestrator.domain.journey.strategy.StrategyTestFixtures.account
import com.example.dpop.orchestrator.domain.journey.strategy.StrategyTestFixtures.ctx
import com.example.dpop.orchestrator.domain.journey.strategy.StrategyTestFixtures.method
import com.example.dpop.orchestrator.domain.journey.strategy.StrategyTestFixtures.evidence
import com.example.dpop.tool_spi.AcrLevel
import com.example.dpop.tool_spi.EnrollmentRef
import com.example.dpop.tool_spi.FactorType
import com.example.dpop.tool_spi.ToolId
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
        val state = ManageAuthMethodsState.Enrolling(Offer(listOf(ToolId("enroll-sms"))))

        then("Enrolled binds the device - it's already known, so this is a harmless no-op that keeps it reachable") {
            val outcome = ToolOutcome.Completed.Enrolled(enrollmentRef = EnrollmentRef("sms", "ref"))
            val event = JourneyEvent.Completed(AuthSmsDescriptor, outcome)
            strategy.transition(state, event, ctx()) shouldBe
                Transition.Perform(Action.AdoptCredential(AuthSmsDescriptor, outcome), resumeState = state)
        }

        then("Identified is not offered by this intent") {
            shouldThrow<IllegalStateException> {
                strategy.transition(state, JourneyEvent.Completed(IdentFscDescriptor, ToolOutcome.Completed.Identified(claims = listOf(com.example.dpop.tool_spi.Claim(com.example.dpop.tool_spi.AttributeType.PERSON_ID, "P000000001", com.example.dpop.tool_spi.ClaimSource.PERSON_DIRECTORY)))), ctx())
            }
        }

        then("Authenticated is not offered by this intent") {
            shouldThrow<IllegalStateException> {
                strategy.transition(state, JourneyEvent.Completed(AuthSmsDescriptor, ToolOutcome.Completed.Authenticated(amr = listOf("sms"))), ctx())
            }
        }
    }

    given("AddRequested, the session does not yet carry loa2") {
        val acc = account(method("sms", AcrLevel.LOA1))
        val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc))
        then("parks the wish and demands a step-up first, without losing it") {
            strategy.transition(ManageAuthMethodsState.AddRequested, JourneyEvent.Started, theCtx) shouldBe
                Transition.RequireSubJourney(
                    AuthIntent.STEP_UP,
                    seedWith = StepUpState.forSubJourney(AcrLevel.LOA2, AcrLevel.LOA1),
                    resumeWith = ManageAuthMethodsState.AddRequested
                )
        }
    }

    given("AddRequested, a never-identified account (personId == null) sitting at loa1") {
        val acc = account(method("sms", AcrLevel.LOA1), personId = null)
        val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc))

        then("selfServiceAcrFloor only demands loa1 for this account - offers enrollment candidates directly, no step-up") {
            val transition = strategy.transition(ManageAuthMethodsState.AddRequested, JourneyEvent.Started, theCtx)
            transition.shouldBeInstanceOf<Transition.To>()
        }
    }

    given("RemoveRequested, a never-identified account (personId == null) sitting at loa1") {
        val acc = account(method("sms", AcrLevel.LOA1), method("password", AcrLevel.LOA1), personId = null)
        val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc))
        val state = ManageAuthMethodsState.RemoveRequested("sms-instance")

        then("selfServiceAcrFloor only demands loa1 for this account - removes the method directly, no step-up") {
            strategy.transition(state, JourneyEvent.Started, theCtx) shouldBe
                Transition.Perform(Action.RevokeAuthMethod("sms-instance"), resumeState = state)
        }
    }

    given("AddRequested, the session already carries loa2") {
        val acc = account(method("sms", AcrLevel.LOA1))
        val theCtx = ctx(account = acc, evidence = evidence(listOf("fsc"), setOf(FactorType.POSSESSION), account = acc), acrFloor = AcrLevel.LOA1)

        then("offers enrollment candidates directly") {
            val transition = strategy.transition(ManageAuthMethodsState.AddRequested, JourneyEvent.Started, theCtx)
            transition.shouldBeInstanceOf<Transition.To>()
        }
    }

    given("AddRequested, loa2 satisfied but nothing left to enroll") {
        val acc = account(
            method("sms", AcrLevel.LOA2), method("password", AcrLevel.LOA2), method("email", AcrLevel.LOA2),
            method("device", AcrLevel.LOA2), method("kobil", AcrLevel.LOA2), method("qr", AcrLevel.LOA2)
        )
        val theCtx = ctx(account = acc, evidence = evidence(listOf("fsc"), setOf(FactorType.POSSESSION), account = acc), acrFloor = AcrLevel.LOA1)

        then("finishes - not an error, just nothing more to add (the device-bound methods stay offered since they allow multiple instances, so this really only fires once every singleton method is active)") {
            // device and kobil (allowsMultipleInstances) are deliberately still offered even with
            // one active instance, so this case is only reachable by ALSO backend-disabling them.
            val disabled = theCtx.availableTools - ToolId("enroll-device") - ToolId("enroll-kobil")
            val transition = strategy.transition(ManageAuthMethodsState.AddRequested, JourneyEvent.Started, theCtx.copy(availableTools = disabled))
            transition shouldBe Transition.Authenticated
        }
    }

    given("AddRequested, resumed after the gate's own STEP_UP was declined (SubJourneyCancelled)") {
        val acc = account(method("sms", AcrLevel.LOA1))
        val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc))
        then("gives up on the wish rather than re-requesting the identical STEP_UP again") {
            strategy.transition(ManageAuthMethodsState.AddRequested, JourneyEvent.SubJourneyCancelled(AuthIntent.STEP_UP), theCtx) shouldBe
                Transition.Cancel
        }
    }

    given("RemoveRequested, the session does not yet carry loa2") {
        val acc = account(method("sms", AcrLevel.LOA1))
        val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc))
        val state = ManageAuthMethodsState.RemoveRequested("sms-instance")

        then("parks the wish and demands a step-up first") {
            strategy.transition(state, JourneyEvent.Started, theCtx) shouldBe
                Transition.RequireSubJourney(
                    AuthIntent.STEP_UP,
                    seedWith = StepUpState.forSubJourney(AcrLevel.LOA2, AcrLevel.LOA1),
                    resumeWith = state
                )
        }
    }

    given("RemoveRequested, the session already carries loa2") {
        val acc = account(method("sms", AcrLevel.LOA2))
        val theCtx = ctx(account = acc, evidence = evidence(listOf("fsc"), setOf(FactorType.POSSESSION), account = acc), acrFloor = AcrLevel.LOA1)
        val state = ManageAuthMethodsState.RemoveRequested("sms-instance")

        then("removes the method directly, then finishes once resumed - the machine, not this strategy, rejects self-lockout") {
            strategy.transition(state, JourneyEvent.Started, theCtx) shouldBe
                Transition.Perform(Action.RevokeAuthMethod("sms-instance"), resumeState = state)
            strategy.transition(state, JourneyEvent.ActionCompleted, theCtx) shouldBe Transition.Authenticated
        }
    }

    given("RemoveRequested, resumed after the gate's own STEP_UP was declined (SubJourneyCancelled)") {
        val acc = account(method("sms", AcrLevel.LOA1))
        val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc))
        val state = ManageAuthMethodsState.RemoveRequested("sms-instance")
        then("gives up on the wish rather than re-requesting the identical STEP_UP again") {
            strategy.transition(state, JourneyEvent.SubJourneyCancelled(AuthIntent.STEP_UP), theCtx) shouldBe Transition.Cancel
        }
    }

    given("Enrolling") {
        val state = ManageAuthMethodsState.Enrolling(Offer(listOf(ToolId("enroll-sms"), ToolId("enroll-password"))))

        `when`("a tool is abandoned") {
            then("stays in Enrolling with the full choice back - not a decline, just picking differently") {
                strategy.transition(state, JourneyEvent.Abandoned(AuthSmsDescriptor), ctx()) shouldBe
                    Transition.To(state.withActive(null))
            }
        }

        `when`("a method is enrolled") {
            then("adopts the credential, then finishes once resumed - one successful enrollment is always enough here") {
                val outcome = ToolOutcome.Completed.Enrolled(enrollmentRef = EnrollmentRef("sms", "ref"))
                val event = JourneyEvent.Completed(AuthSmsDescriptor, outcome)
                strategy.transition(state, event, ctx()) shouldBe
                    Transition.Perform(Action.AdoptCredential(AuthSmsDescriptor, outcome), resumeState = state)
                strategy.transition(state, JourneyEvent.ActionCompleted, ctx()) shouldBe Transition.Authenticated
            }
        }
    }
})
