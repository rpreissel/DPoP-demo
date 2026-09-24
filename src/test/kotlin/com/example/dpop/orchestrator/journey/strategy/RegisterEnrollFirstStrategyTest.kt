package com.example.dpop.orchestrator.journey.strategy

import com.example.dpop.orchestrator.kernel.ChannelType
import com.example.dpop.auth_email.ConfirmEmailDescriptor
import com.example.dpop.auth_password.EnrollPasswordDescriptor
import com.example.dpop.auth_sms.AuthSmsUseDescriptor
import com.example.dpop.auth_sms.EnrollSmsDescriptor
import com.example.dpop.orchestrator.journey.Action
import com.example.dpop.orchestrator.kernel.AuthIntent
import com.example.dpop.orchestrator.journey.JourneyEvent
import com.example.dpop.orchestrator.journey.Transition
import com.example.dpop.orchestrator.journey.state.Offer
import com.example.dpop.orchestrator.journey.state.ReIdentifyState
import com.example.dpop.orchestrator.journey.state.RegisterEnrollFirstState
import com.example.dpop.orchestrator.journey.strategy.StrategyTestFixtures.account
import com.example.dpop.orchestrator.journey.strategy.StrategyTestFixtures.ctx
import com.example.dpop.orchestrator.journey.strategy.StrategyTestFixtures.evidence
import com.example.dpop.orchestrator.journey.strategy.StrategyTestFixtures.method
import com.example.dpop.orchestrator.session.ChannelState
import com.example.dpop.tool_spi.AcrLevel
import com.example.dpop.tool_spi.EnrollmentRef
import com.example.dpop.tool_spi.FactorType
import com.example.dpop.tool_spi.AttributeType
import com.example.dpop.tool_spi.Claim
import com.example.dpop.tool_spi.ClaimSource
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
    val enrollFirstIdentificationWording = ReIdentifyState.Wording.OPTIONAL_IDENTIFICATION

    given("the intent") {
        then("is REGISTER, same as the ident-first variant - only the dispatcher tells them apart") {
            strategy.intent shouldBe AuthIntent.REGISTER
        }
    }

    given("Start, no account known yet") {
        then("offers EMAIL enrollment first, mandatory - not the whole menu, no identification step yet") {
            strategy.transition(RegisterEnrollFirstState.EnrollFirstStart, JourneyEvent.Started, ctx(account = null)) shouldBe
                Transition.To(RegisterEnrollFirstState.EnrollFirstAttestingEmail(Offer(listOf(ToolId("confirm-email")))))
        }
    }

    given("Start, email enrollment tool unavailable right now (admin-disabled)") {
        then("skips straight to the mandatory SMS step instead of blocking the journey") {
            val theCtx = ctx(account = null, availableTools = StrategyTestFixtures.allToolIds - ToolId("confirm-email"))
            strategy.transition(RegisterEnrollFirstState.EnrollFirstStart, JourneyEvent.Started, theCtx) shouldBe
                Transition.To(RegisterEnrollFirstState.EnrollFirstEnrollingSms(Offer(listOf(ToolId("enroll-sms")))))
        }
    }

    given("Start, neither email nor SMS enrollment tool available") {
        then("falls back to the old free-choice-among-everything offer instead of crashing on a missing account") {
            val theCtx = ctx(account = null, availableTools = StrategyTestFixtures.allToolIds - ToolId("confirm-email") - ToolId("enroll-sms"))
            strategy.transition(RegisterEnrollFirstState.EnrollFirstStart, JourneyEvent.Started, theCtx)
                .shouldBeEnrollingWith("enroll-device", "enroll-kobil", "enroll-qr")
        }
    }

    given("EnrollFirstAttestingEmail, email just enrolled") {
        val acc = account(method("email", AcrLevel.LOA1), emailConfirmed = true)
        val theCtx = ctx(account = acc, evidence = evidence(listOf("email"), setOf(FactorType.POSSESSION), account = acc), acrFloor = AcrLevel.LOA1)
        val state = RegisterEnrollFirstState.EnrollFirstAttestingEmail(Offer(listOf(ToolId("confirm-email"))))

        then("adopts the credential, then moves on to the mandatory SMS step - not to the free-choice menu") {
            val outcome = ToolOutcome.Completed.Enrolled(enrollmentRef = EnrollmentRef("email", "ref"))
            val event = JourneyEvent.Completed(ConfirmEmailDescriptor, outcome)
            strategy.transition(state, event, theCtx) shouldBe
                Transition.Perform(Action.AdoptCredential(ConfirmEmailDescriptor, outcome), resumeState = state)
            strategy.transition(state, JourneyEvent.ActionCompleted, theCtx) shouldBe
                Transition.To(RegisterEnrollFirstState.EnrollFirstEnrollingSms(Offer(listOf(ToolId("enroll-sms")))))
        }
    }

    given("EnrollFirstAttestingEmail, more than one offered candidate") {
        val state = RegisterEnrollFirstState.EnrollFirstAttestingEmail(Offer(listOf(ToolId("confirm-email"))))
        then("abandoning re-offers the same mandatory step, no skipping ahead to SMS") {
            strategy.transition(state, JourneyEvent.Abandoned(ConfirmEmailDescriptor), ctx()) shouldBe
                Transition.To(state.withActive(null))
        }
    }

    given("EnrollFirstEnrollingSms, sms just enrolled, floor reached, but email is still unconfirmed") {
        val acc = account(method("sms", AcrLevel.LOA1), emailConfirmed = false)
        val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc), acrFloor = AcrLevel.LOA1)
        val state = RegisterEnrollFirstState.EnrollFirstEnrollingSms(Offer(listOf(ToolId("enroll-sms"))))

        then("adopts the credential, then falls into the normal obligation cascade - email is always obligatory here") {
            val outcome = ToolOutcome.Completed.Enrolled(enrollmentRef = EnrollmentRef("sms", "ref"))
            val event = JourneyEvent.Completed(AuthSmsUseDescriptor, outcome)
            strategy.transition(state, event, theCtx) shouldBe
                Transition.Perform(Action.AdoptCredential(AuthSmsUseDescriptor, outcome), resumeState = state)
            strategy.transition(state, JourneyEvent.ActionCompleted, theCtx) shouldBe
                Transition.To(RegisterEnrollFirstState.EnrollFirstConfirmingEmail(Offer(listOf(ToolId("confirm-email")))))
        }
    }

    given("EnrollFirstEnrollingSms, more than one offered candidate") {
        val state = RegisterEnrollFirstState.EnrollFirstEnrollingSms(Offer(listOf(ToolId("enroll-sms"))))
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
        val state = RegisterEnrollFirstState.EnrollFirstEnrolling(Offer(listOf(ToolId("enroll-sms"), ToolId("confirm-email"))))
        then("abandoning re-offers the FULL choice - this is a mandatory state, not a fallback") {
            strategy.transition(state, JourneyEvent.Abandoned(EnrollSmsDescriptor), ctx()) shouldBe
                Transition.To(state.withActive(null))
        }
    }

    given("Enrolling, a method was just enrolled, floor reached, but email is still unconfirmed") {
        val acc = account(method("sms", AcrLevel.LOA1), emailConfirmed = false)
        val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc), acrFloor = AcrLevel.LOA1)
        val state = RegisterEnrollFirstState.EnrollFirstEnrolling(Offer(listOf(ToolId("enroll-sms"))))

        then("adopts the credential, then moves on to ConfirmingEmail - email is always obligatory here") {
            val outcome = ToolOutcome.Completed.Enrolled(enrollmentRef = EnrollmentRef("sms", "ref"))
            val event = JourneyEvent.Completed(AuthSmsUseDescriptor, outcome)
            strategy.transition(state, event, theCtx) shouldBe
                Transition.Perform(Action.AdoptCredential(AuthSmsUseDescriptor, outcome), resumeState = state)
            strategy.transition(state, JourneyEvent.ActionCompleted, theCtx) shouldBe
                Transition.To(RegisterEnrollFirstState.EnrollFirstConfirmingEmail(Offer(listOf(ToolId("confirm-email")))))
        }
    }

    given("ConfirmingEmail, confirmed, on the APP channel - the password obligation applies here too") {
        val acc = account(method("sms", AcrLevel.LOA1), emailConfirmed = true)
        val theCtx = ctx(
            account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc),
            acrFloor = AcrLevel.LOA1, channel = ChannelType.APP
        )
        val state = RegisterEnrollFirstState.EnrollFirstConfirmingEmail(Offer(listOf(ToolId("confirm-email"))))

        // The address is attested, so the remaining obligation is the password - the knowledge
        // factor that confirming an email used to leave behind as a side effect.
        then("the address is attested, then the password is asked for") {
            val outcome = ToolOutcome.Completed.Attested(
                claims = listOf(Claim(AttributeType.EMAIL, "max@example.com", ClaimSource.of(ToolId("confirm-email"))))
            )
            val event = JourneyEvent.Completed(ConfirmEmailDescriptor, outcome)
            strategy.transition(state, event, theCtx) shouldBe
                Transition.Perform(Action.AdoptAttestation(ConfirmEmailDescriptor, outcome), resumeState = state)
            strategy.transition(state, JourneyEvent.ActionCompleted, theCtx) shouldBe
                Transition.To(RegisterEnrollFirstState.EnrollFirstPasswordObligation(Offer(listOf(ToolId("enroll-password")))))
        }
    }

    given("ConfirmingEmail, confirmed, on the KEYCLOAK channel with no active password yet") {
        val acc = account(method("sms", AcrLevel.LOA1), emailConfirmed = true)
        val theCtx = ctx(
            account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc),
            acrFloor = AcrLevel.LOA1, channel = ChannelType.KEYCLOAK
        )
        val state = RegisterEnrollFirstState.EnrollFirstConfirmingEmail(Offer(listOf(ToolId("confirm-email"))))

        then("falls through to the still-open password obligation, not to the identification offer yet") {
            val outcome = ToolOutcome.Completed.Enrolled(enrollmentRef = EnrollmentRef("email", "ref"))
            val event = JourneyEvent.Completed(ConfirmEmailDescriptor, outcome)
            strategy.transition(state, event, theCtx) shouldBe
                Transition.Perform(Action.AdoptCredential(ConfirmEmailDescriptor, outcome), resumeState = state)
            strategy.transition(state, JourneyEvent.ActionCompleted, theCtx) shouldBe
                Transition.To(RegisterEnrollFirstState.EnrollFirstPasswordObligation(Offer(listOf(ToolId("enroll-password")))))
        }
    }

    given("PasswordObligation, fulfilled - every obligation now discharged") {
        val acc = account(method("sms", AcrLevel.LOA1), method("password", AcrLevel.LOA1), emailConfirmed = true)
        val theCtx = ctx(
            account = acc,
            evidence = evidence(listOf("sms", "password"), setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE), account = acc),
            acrFloor = AcrLevel.LOA1, channel = ChannelType.KEYCLOAK
        )
        val state = RegisterEnrollFirstState.EnrollFirstPasswordObligation(Offer(listOf(ToolId("enroll-password"))))

        then("offers the optional identification step - the real catalog's ident tools can still close it") {
            val outcome = ToolOutcome.Completed.Enrolled(enrollmentRef = EnrollmentRef("password", "ref"))
            val event = JourneyEvent.Completed(EnrollPasswordDescriptor, outcome)
            strategy.transition(state, event, theCtx) shouldBe
                Transition.Perform(Action.AdoptCredential(EnrollPasswordDescriptor, outcome), resumeState = state)
            val transition = strategy.transition(state, JourneyEvent.ActionCompleted, theCtx)
            transition.shouldBeInstanceOf<Transition.RequireSubJourney>()
            (transition as Transition.RequireSubJourney).intent shouldBe AuthIntent.RE_IDENTIFY
        }
    }

    given("onCancel") {
        then("always falls back to ANONYMOUS, same as the ident-first variant") {
            strategy.cancelledTo(RegisterEnrollFirstState.EnrollFirstStart) shouldBe ChannelState.ANONYMOUS
            strategy.cancelledTo(RegisterEnrollFirstState.EnrollFirstEnrolling(Offer(listOf(ToolId("enroll-sms"))))) shouldBe ChannelState.ANONYMOUS
        }
    }
})

private fun Transition.shouldBeEnrollingWith(vararg toolIds: String) {
    require(this is Transition.To) { "expected Transition.To, was $this" }
    val to = state
    require(to is RegisterEnrollFirstState.EnrollFirstEnrolling) { "expected RegisterEnrollFirstState.EnrollFirstEnrolling, was $to" }
    to.offered shouldContainExactlyInAnyOrder toolIds.map { ToolId(it) }
}
