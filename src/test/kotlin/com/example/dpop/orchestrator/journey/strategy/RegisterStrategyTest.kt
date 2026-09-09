package com.example.dpop.orchestrator.journey.strategy

import com.example.dpop.auth_password.EnrollPasswordDescriptor
import com.example.dpop.auth_sms.AuthSmsUseDescriptor
import com.example.dpop.orchestrator.journey.Action
import com.example.dpop.orchestrator.journey.AuthIntent
import com.example.dpop.orchestrator.journey.JourneyEvent
import com.example.dpop.orchestrator.journey.Transition
import com.example.dpop.orchestrator.journey.state.FastAccessState
import com.example.dpop.orchestrator.journey.strategy.StrategyTestFixtures.account
import com.example.dpop.orchestrator.journey.strategy.StrategyTestFixtures.ctx
import com.example.dpop.orchestrator.journey.strategy.StrategyTestFixtures.deviceDetails
import com.example.dpop.orchestrator.journey.strategy.StrategyTestFixtures.evidence
import com.example.dpop.orchestrator.journey.strategy.StrategyTestFixtures.method
import com.example.dpop.orchestrator.session.ChannelSession
import com.example.dpop.tool_spi.EnrollmentRef
import com.example.dpop.tool_spi.FactorType
import com.example.dpop.tool_spi.ToolOutcome
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Pure unit coverage of [RegisterStrategy] - deliberately fresh identification even on an already
 * linked device (docs/04-orchestrierung.md, "REGISTER"). Everything from identification onward is
 * inherited from [FastAccessStrategy] unchanged and already covered by [FastAccessStrategyTest];
 * this only has to prove the one thing REGISTER changes: where the chain starts.
 */
class RegisterStrategyTest : BehaviorSpec({

    val strategy = RegisterStrategy()

    given("the intent") {
        then("is REGISTER") {
            strategy.intent shouldBe AuthIntent.REGISTER
        }
    }

    given("Start, even with a linked device credential and other active methods") {
        val acc = account(method("device", "loa2", details = deviceDetails()), method("sms", "loa2"))

        then("skips PreferredAuth/AuthChoice entirely and goes straight to identification") {
            val transition = strategy.transition(FastAccessState.Start, JourneyEvent.Started, ctx(account = acc))
            transition.shouldBeInstanceOf<Transition.To>()
            (transition as Transition.To).state.shouldBeInstanceOf<FastAccessState.Identifying>()
        }
    }

    given("Start, no account known yet") {
        then("behaves exactly like FAST_ACCESS in this case - both fall to identification") {
            val transition = strategy.transition(FastAccessState.Start, JourneyEvent.Started, ctx(account = null))
            transition.shouldBeInstanceOf<Transition.To>()
            (transition as Transition.To).state.shouldBeInstanceOf<FastAccessState.Identifying>()
        }
    }

    // The Web-only third obligation (docs/04-orchestrierung.md #8): registering via KEYCLOAK must
    // always end up with a password credential, in addition to the shared email obligation.
    // Ordered AFTER the email obligation, never before: enroll-password itself requires a
    // confirmed email (ToolDescriptor.requiresConfirmedEmail) - see PasswordObligation's own KDoc.
    given("Enrolling on the KEYCLOAK channel, sufficient, email already confirmed, no active password") {
        val acc = account(method("sms", "loa1"), emailConfirmed = true)
        val theCtx = ctx(
            account = acc,
            evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc),
            acrFloor = "loa1",
            channel = ChannelSession.Channel.KEYCLOAK
        )
        val state = FastAccessState.Enrolling(listOf("enroll-sms", "enroll-password"), emailObligation = false)

        then("adopts the credential, then moves on to PasswordObligation instead of finishing") {
            val outcome = ToolOutcome.Completed.Enrolled(enrollmentRef = EnrollmentRef("sms", "ref"))
            val event = JourneyEvent.Completed(AuthSmsUseDescriptor, outcome)
            strategy.transition(state, event, theCtx) shouldBe
                Transition.Perform(Action.AdoptCredential(AuthSmsUseDescriptor, outcome, bindDevice = true), resumeState = state)
            strategy.transition(state, JourneyEvent.ActionCompleted, theCtx) shouldBe
                Transition.To(FastAccessState.PasswordObligation(listOf("enroll-password")))
        }
    }

    given("Enrolling on the KEYCLOAK channel, sufficient, but the email obligation is still open") {
        val acc = account(method("sms", "loa1"), emailConfirmed = false)
        val theCtx = ctx(
            account = acc,
            evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc),
            acrFloor = "loa1",
            channel = ChannelSession.Channel.KEYCLOAK
        )
        // enroll-password isn't even a candidate yet without a confirmed email
        // (AuthPolicy.enrollmentCandidates, docs/03-tool-architektur.md #1), consistent with
        // reality here.
        val state = FastAccessState.Enrolling(listOf("enroll-sms"), emailObligation = true)

        then("ConfirmingEmail comes first - password is never even considered until the email is confirmed") {
            val outcome = ToolOutcome.Completed.Enrolled(enrollmentRef = EnrollmentRef("sms", "ref"))
            val event = JourneyEvent.Completed(AuthSmsUseDescriptor, outcome)
            strategy.transition(state, event, theCtx) shouldBe
                Transition.Perform(Action.AdoptCredential(AuthSmsUseDescriptor, outcome, bindDevice = true), resumeState = state)
            strategy.transition(state, JourneyEvent.ActionCompleted, theCtx) shouldBe
                Transition.To(FastAccessState.ConfirmingEmail(listOf("enroll-email")))
        }
    }

    given("Enrolling on the KEYCLOAK channel, but the user chose enroll-password directly (email already confirmed)") {
        val acc = account(method("password", "loa1"), emailConfirmed = true)
        val theCtx = ctx(
            account = acc,
            evidence = evidence(listOf("password"), setOf(FactorType.KNOWLEDGE), account = acc),
            acrFloor = "loa1",
            channel = ChannelSession.Channel.KEYCLOAK
        )
        val state = FastAccessState.Enrolling(listOf("enroll-sms", "enroll-password"), emailObligation = false)

        then("the obligation is already satisfied - finishes directly, no PasswordObligation detour") {
            val outcome = ToolOutcome.Completed.Enrolled(enrollmentRef = EnrollmentRef("password", "ref"))
            val event = JourneyEvent.Completed(EnrollPasswordDescriptor, outcome)
            strategy.transition(state, event, theCtx) shouldBe
                Transition.Perform(Action.AdoptCredential(EnrollPasswordDescriptor, outcome, bindDevice = true), resumeState = state)
            strategy.transition(state, JourneyEvent.ActionCompleted, theCtx) shouldBe Transition.Authenticated
        }
    }

    given("Enrolling on the APP channel - the same scenario that would trigger PasswordObligation on the Web") {
        val acc = account(method("sms", "loa1"), emailConfirmed = true)
        val theCtx = ctx(
            account = acc,
            evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc),
            acrFloor = "loa1",
            channel = ChannelSession.Channel.APP
        )
        val state = FastAccessState.Enrolling(listOf("enroll-sms", "enroll-password"), emailObligation = false)

        then("the obligation never applies on APP - finishes directly, exactly like FAST_ACCESS") {
            val outcome = ToolOutcome.Completed.Enrolled(enrollmentRef = EnrollmentRef("sms", "ref"))
            val event = JourneyEvent.Completed(AuthSmsUseDescriptor, outcome)
            strategy.transition(state, event, theCtx) shouldBe
                Transition.Perform(Action.AdoptCredential(AuthSmsUseDescriptor, outcome, bindDevice = true), resumeState = state)
            strategy.transition(state, JourneyEvent.ActionCompleted, theCtx) shouldBe Transition.Authenticated
        }
    }

    given("Enrolling on KEYCLOAK, but the Web theme never declared enroll-password renderable") {
        val acc = account(method("sms", "loa1"), emailConfirmed = true)
        val theCtx = ctx(
            account = acc,
            evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc),
            acrFloor = "loa1",
            channel = ChannelSession.Channel.KEYCLOAK,
            availableTools = StrategyTestFixtures.allToolIds - "enroll-password"
        )
        val state = FastAccessState.Enrolling(listOf("enroll-sms"), emailObligation = false)

        then("the obligation can't be enforced without a renderer - finishes directly instead of dead-ending") {
            val outcome = ToolOutcome.Completed.Enrolled(enrollmentRef = EnrollmentRef("sms", "ref"))
            val event = JourneyEvent.Completed(AuthSmsUseDescriptor, outcome)
            strategy.transition(state, event, theCtx) shouldBe
                Transition.Perform(Action.AdoptCredential(AuthSmsUseDescriptor, outcome, bindDevice = true), resumeState = state)
            strategy.transition(state, JourneyEvent.ActionCompleted, theCtx) shouldBe Transition.Authenticated
        }
    }

    given("ConfirmingEmail on the KEYCLOAK channel, confirmed, no active password yet") {
        val acc = account(method("sms", "loa1"), emailConfirmed = true)
        val theCtx = ctx(
            account = acc,
            evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc),
            acrFloor = "loa1",
            channel = ChannelSession.Channel.KEYCLOAK
        )
        val state = FastAccessState.ConfirmingEmail(listOf("enroll-email"))

        then("the discharged email obligation falls through to the still-open password obligation, not straight to Authenticated") {
            val outcome = ToolOutcome.Completed.Enrolled(enrollmentRef = EnrollmentRef("email", "ref"))
            val event = JourneyEvent.Completed(com.example.dpop.auth_email.EnrollEmailDescriptor, outcome)
            strategy.transition(state, event, theCtx) shouldBe
                Transition.Perform(Action.AdoptCredential(com.example.dpop.auth_email.EnrollEmailDescriptor, outcome, bindDevice = true), resumeState = state)
            strategy.transition(state, JourneyEvent.ActionCompleted, theCtx) shouldBe
                Transition.To(FastAccessState.PasswordObligation(listOf("enroll-password")))
        }
    }

    given("PasswordObligation") {
        `when`("abandoned") {
            val state = FastAccessState.PasswordObligation(listOf("enroll-password"))
            then("re-offers the same full choice - the obligation itself is never waived by backing out") {
                strategy.transition(state, JourneyEvent.Abandoned(EnrollPasswordDescriptor), ctx(channel = ChannelSession.Channel.KEYCLOAK)) shouldBe
                    Transition.To(state.withActive(null))
            }
        }

        `when`("fulfilled") {
            val acc = account(method("sms", "loa1"), method("password", "loa1"), emailConfirmed = true)
            val theCtx = ctx(
                account = acc,
                evidence = evidence(listOf("sms", "password"), setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE), account = acc),
                acrFloor = "loa1",
                channel = ChannelSession.Channel.KEYCLOAK
            )
            val state = FastAccessState.PasswordObligation(listOf("enroll-password"))
            then("adopts the credential, then finishes - every obligation is now discharged") {
                val outcome = ToolOutcome.Completed.Enrolled(enrollmentRef = EnrollmentRef("password", "ref"))
                val event = JourneyEvent.Completed(EnrollPasswordDescriptor, outcome)
                strategy.transition(state, event, theCtx) shouldBe
                    Transition.Perform(Action.AdoptCredential(EnrollPasswordDescriptor, outcome, bindDevice = true), resumeState = state)
                strategy.transition(state, JourneyEvent.ActionCompleted, theCtx) shouldBe Transition.Authenticated
            }
        }
    }
})
