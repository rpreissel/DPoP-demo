package com.example.dpop.orchestrator.journey.strategy

import com.example.dpop.auth_email.EnrollEmailDescriptor
import com.example.dpop.auth_password.EnrollPasswordDescriptor
import com.example.dpop.auth_sms.AuthSmsUseDescriptor
import com.example.dpop.auth_sms.EnrollSmsDescriptor
import com.example.dpop.orchestrator.journey.Action
import com.example.dpop.orchestrator.journey.AuthIntent
import com.example.dpop.orchestrator.journey.JourneyEvent
import com.example.dpop.orchestrator.journey.Transition
import com.example.dpop.orchestrator.journey.state.ReIdentifyState
import com.example.dpop.orchestrator.journey.state.RegisterEnrollFirstState
import com.example.dpop.orchestrator.journey.strategy.StrategyTestFixtures.account
import com.example.dpop.orchestrator.journey.strategy.StrategyTestFixtures.ctx
import com.example.dpop.orchestrator.journey.strategy.StrategyTestFixtures.evidence
import com.example.dpop.orchestrator.journey.strategy.StrategyTestFixtures.method
import com.example.dpop.orchestrator.session.ChannelSession
import com.example.dpop.orchestrator.session.ChannelState
import com.example.dpop.tool_spi.AcrLevel
import com.example.dpop.tool_spi.EnrollmentRef
import com.example.dpop.tool_spi.FactorType
import com.example.dpop.tool_spi.ToolId
import com.example.dpop.tool_spi.ToolOutcome
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Pure unit coverage of [RegisterEnrollFirstStrategy] - the "Enrollment zuerst" experiment
 * (docs/04-orchestrierung.md, REGISTER). Fully autark from [RegisterStrategy]/`AuthEnrollCore` -
 * see [RegisterEnrollFirstState]'s own doc - so this exercises its own transition function in
 * full, the same way [RegisterStrategyTest] does for the ident-first journey.
 */
class RegisterEnrollFirstStrategyTest : BehaviorSpec({

    val strategy = RegisterEnrollFirstStrategy()

    // Mirrors the literal RegisterEnrollFirstStrategy.offerIdentificationOrFinish builds - its
    // closing offer needs its own wording (never identified before, nothing "nicht erreichbar"),
    // not RE_IDENTIFY's shared default text.
    val enrollFirstIdentificationWording = ReIdentifyState.Wording(
        offerTitle = "Identifizieren?",
        offerDescription = "Sie sind bereits angemeldet. Optional können Sie sich jetzt zusätzlich identifizieren.",
        offerConfirmLabel = "Identifizieren",
        selectionTitle = "Identifikation (optional)",
        selectionDescription = "Wählen Sie ein Verfahren, um sich zu identifizieren."
    )

    given("the intent") {
        then("is REGISTER, same as the ident-first variant - only the dispatcher tells them apart") {
            strategy.intent shouldBe AuthIntent.REGISTER
        }
    }

    given("Start, no account known yet") {
        then("offers EMAIL enrollment first, mandatory - not the whole menu, no identification step yet") {
            strategy.transition(RegisterEnrollFirstState.EnrollFirstStart, JourneyEvent.Started, ctx(account = null)) shouldBe
                Transition.To(RegisterEnrollFirstState.EnrollFirstEnrollingEmail(listOf(ToolId("enroll-email"))))
        }
    }

    given("Start, email enrollment tool unavailable right now (admin-disabled)") {
        then("skips straight to the mandatory SMS step instead of blocking the journey") {
            val theCtx = ctx(account = null, availableTools = StrategyTestFixtures.allToolIds - ToolId("enroll-email"))
            strategy.transition(RegisterEnrollFirstState.EnrollFirstStart, JourneyEvent.Started, theCtx) shouldBe
                Transition.To(RegisterEnrollFirstState.EnrollFirstEnrollingSms(listOf(ToolId("enroll-sms"))))
        }
    }

    given("Start, neither email nor SMS enrollment tool available") {
        then("falls back to the old free-choice-among-everything offer instead of crashing on a missing account") {
            val theCtx = ctx(account = null, availableTools = StrategyTestFixtures.allToolIds - ToolId("enroll-email") - ToolId("enroll-sms"))
            strategy.transition(RegisterEnrollFirstState.EnrollFirstStart, JourneyEvent.Started, theCtx)
                .shouldBeEnrollingWith("enroll-device", "enroll-qr")
        }
    }

    given("EnrollFirstEnrollingEmail, email just enrolled") {
        val acc = account(method("email", AcrLevel.LOA1), emailConfirmed = true)
        val theCtx = ctx(account = acc, evidence = evidence(listOf("email"), setOf(FactorType.POSSESSION), account = acc), acrFloor = AcrLevel.LOA1)
        val state = RegisterEnrollFirstState.EnrollFirstEnrollingEmail(listOf(ToolId("enroll-email")))

        then("adopts the credential, then moves on to the mandatory SMS step - not to the free-choice menu") {
            val outcome = ToolOutcome.Completed.Enrolled(enrollmentRef = EnrollmentRef("email", "ref"))
            val event = JourneyEvent.Completed(EnrollEmailDescriptor, outcome)
            strategy.transition(state, event, theCtx) shouldBe
                Transition.Perform(Action.AdoptCredential(EnrollEmailDescriptor, outcome, bindDevice = true), resumeState = state)
            strategy.transition(state, JourneyEvent.ActionCompleted, theCtx) shouldBe
                Transition.To(RegisterEnrollFirstState.EnrollFirstEnrollingSms(listOf(ToolId("enroll-sms"))))
        }
    }

    given("EnrollFirstEnrollingEmail, more than one offered candidate") {
        val state = RegisterEnrollFirstState.EnrollFirstEnrollingEmail(listOf(ToolId("enroll-email")))
        then("abandoning re-offers the same mandatory step, no skipping ahead to SMS") {
            strategy.transition(state, JourneyEvent.Abandoned(EnrollEmailDescriptor), ctx()) shouldBe
                Transition.To(state.withActive(null))
        }
    }

    given("EnrollFirstEnrollingSms, sms just enrolled, floor reached, but email is still unconfirmed") {
        val acc = account(method("sms", AcrLevel.LOA1), emailConfirmed = false)
        val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc), acrFloor = AcrLevel.LOA1)
        val state = RegisterEnrollFirstState.EnrollFirstEnrollingSms(listOf(ToolId("enroll-sms")))

        then("adopts the credential, then falls into the normal obligation cascade - email is always obligatory here") {
            val outcome = ToolOutcome.Completed.Enrolled(enrollmentRef = EnrollmentRef("sms", "ref"))
            val event = JourneyEvent.Completed(AuthSmsUseDescriptor, outcome)
            strategy.transition(state, event, theCtx) shouldBe
                Transition.Perform(Action.AdoptCredential(AuthSmsUseDescriptor, outcome, bindDevice = true), resumeState = state)
            strategy.transition(state, JourneyEvent.ActionCompleted, theCtx) shouldBe
                Transition.To(RegisterEnrollFirstState.EnrollFirstConfirmingEmail(listOf(ToolId("enroll-email"))))
        }
    }

    given("EnrollFirstEnrollingSms, more than one offered candidate") {
        val state = RegisterEnrollFirstState.EnrollFirstEnrollingSms(listOf(ToolId("enroll-sms")))
        then("abandoning re-offers the same mandatory step") {
            strategy.transition(state, JourneyEvent.Abandoned(EnrollSmsDescriptor), ctx()) shouldBe
                Transition.To(state.withActive(null))
        }
    }

    given("Start, resumed after the closing, optional RE_IDENTIFY sub-journey") {
        then("finishes regardless of whether it was accepted-and-succeeded, declined, or abandoned") {
            strategy.transition(RegisterEnrollFirstState.EnrollFirstStart, JourneyEvent.SubJourneyFinished(AuthIntent.RE_IDENTIFY, achievedAcr = AcrLevel.LOA2), ctx()) shouldBe
                Transition.Authenticated
            strategy.transition(RegisterEnrollFirstState.EnrollFirstStart, JourneyEvent.SubJourneyCancelled(AuthIntent.RE_IDENTIFY), ctx()) shouldBe
                Transition.Authenticated
        }
    }

    given("Enrolling, more than one offered candidate") {
        val state = RegisterEnrollFirstState.EnrollFirstEnrolling(listOf(ToolId("enroll-sms"), ToolId("enroll-email")))
        then("abandoning re-offers the FULL choice - this is a mandatory state, not a fallback") {
            strategy.transition(state, JourneyEvent.Abandoned(EnrollSmsDescriptor), ctx()) shouldBe
                Transition.To(state.withActive(null))
        }
    }

    given("Enrolling, a method was just enrolled, floor reached, but email is still unconfirmed") {
        val acc = account(method("sms", AcrLevel.LOA1), emailConfirmed = false)
        val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc), acrFloor = AcrLevel.LOA1)
        val state = RegisterEnrollFirstState.EnrollFirstEnrolling(listOf(ToolId("enroll-sms")))

        then("adopts the credential, then moves on to ConfirmingEmail - email is always obligatory here") {
            val outcome = ToolOutcome.Completed.Enrolled(enrollmentRef = EnrollmentRef("sms", "ref"))
            val event = JourneyEvent.Completed(AuthSmsUseDescriptor, outcome)
            strategy.transition(state, event, theCtx) shouldBe
                Transition.Perform(Action.AdoptCredential(AuthSmsUseDescriptor, outcome, bindDevice = true), resumeState = state)
            strategy.transition(state, JourneyEvent.ActionCompleted, theCtx) shouldBe
                Transition.To(RegisterEnrollFirstState.EnrollFirstConfirmingEmail(listOf(ToolId("enroll-email"))))
        }
    }

    given("ConfirmingEmail, confirmed, on the APP channel (no password obligation there)") {
        val acc = account(method("sms", AcrLevel.LOA1), emailConfirmed = true)
        val theCtx = ctx(
            account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc),
            acrFloor = AcrLevel.LOA1, channel = ChannelSession.Channel.APP
        )
        val state = RegisterEnrollFirstState.EnrollFirstConfirmingEmail(listOf(ToolId("enroll-email")))

        then("every obligation is discharged - offers the optional identification step, not Authenticated directly") {
            val outcome = ToolOutcome.Completed.Enrolled(enrollmentRef = EnrollmentRef("email", "ref"))
            val event = JourneyEvent.Completed(EnrollEmailDescriptor, outcome)
            strategy.transition(state, event, theCtx) shouldBe
                Transition.Perform(Action.AdoptCredential(EnrollEmailDescriptor, outcome, bindDevice = true), resumeState = state)
            strategy.transition(state, JourneyEvent.ActionCompleted, theCtx) shouldBe
                Transition.RequireSubJourney(
                    AuthIntent.RE_IDENTIFY,
                    seedWith = ReIdentifyState.forSubJourney(targetAcr = AcrLevel.LOA1, startingAcr = AcrLevel.LOA1, wording = enrollFirstIdentificationWording),
                    resumeWith = RegisterEnrollFirstState.EnrollFirstStart
                )
        }
    }

    given("ConfirmingEmail, confirmed, on the KEYCLOAK channel with no active password yet") {
        val acc = account(method("sms", AcrLevel.LOA1), emailConfirmed = true)
        val theCtx = ctx(
            account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc),
            acrFloor = AcrLevel.LOA1, channel = ChannelSession.Channel.KEYCLOAK
        )
        val state = RegisterEnrollFirstState.EnrollFirstConfirmingEmail(listOf(ToolId("enroll-email")))

        then("falls through to the still-open password obligation, not to the identification offer yet") {
            val outcome = ToolOutcome.Completed.Enrolled(enrollmentRef = EnrollmentRef("email", "ref"))
            val event = JourneyEvent.Completed(EnrollEmailDescriptor, outcome)
            strategy.transition(state, event, theCtx) shouldBe
                Transition.Perform(Action.AdoptCredential(EnrollEmailDescriptor, outcome, bindDevice = true), resumeState = state)
            strategy.transition(state, JourneyEvent.ActionCompleted, theCtx) shouldBe
                Transition.To(RegisterEnrollFirstState.EnrollFirstPasswordObligation(listOf(ToolId("enroll-password"))))
        }
    }

    given("PasswordObligation, fulfilled - every obligation now discharged") {
        val acc = account(method("sms", AcrLevel.LOA1), method("password", AcrLevel.LOA1), emailConfirmed = true)
        val theCtx = ctx(
            account = acc,
            evidence = evidence(listOf("sms", "password"), setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE), account = acc),
            acrFloor = AcrLevel.LOA1, channel = ChannelSession.Channel.KEYCLOAK
        )
        val state = RegisterEnrollFirstState.EnrollFirstPasswordObligation(listOf(ToolId("enroll-password")))

        then("offers the optional identification step - the real catalog's ident tools can still close it") {
            val outcome = ToolOutcome.Completed.Enrolled(enrollmentRef = EnrollmentRef("password", "ref"))
            val event = JourneyEvent.Completed(EnrollPasswordDescriptor, outcome)
            strategy.transition(state, event, theCtx) shouldBe
                Transition.Perform(Action.AdoptCredential(EnrollPasswordDescriptor, outcome, bindDevice = true), resumeState = state)
            val transition = strategy.transition(state, JourneyEvent.ActionCompleted, theCtx)
            transition.shouldBeInstanceOf<Transition.RequireSubJourney>()
            (transition as Transition.RequireSubJourney).intent shouldBe AuthIntent.RE_IDENTIFY
        }
    }

    given("onCancel") {
        then("always falls back to ANONYMOUS, same as the ident-first variant") {
            strategy.cancelledTo(RegisterEnrollFirstState.EnrollFirstStart) shouldBe ChannelState.ANONYMOUS
            strategy.cancelledTo(RegisterEnrollFirstState.EnrollFirstEnrolling(listOf(ToolId("enroll-sms")))) shouldBe ChannelState.ANONYMOUS
        }
    }
})

private fun Transition.shouldBeEnrollingWith(vararg toolIds: String) {
    require(this is Transition.To) { "expected Transition.To, was $this" }
    val to = state
    require(to is RegisterEnrollFirstState.EnrollFirstEnrolling) { "expected RegisterEnrollFirstState.EnrollFirstEnrolling, was $to" }
    to.offered shouldContainExactlyInAnyOrder toolIds.map { ToolId(it) }
}
