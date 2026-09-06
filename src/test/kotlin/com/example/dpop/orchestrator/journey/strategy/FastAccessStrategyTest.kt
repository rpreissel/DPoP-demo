package com.example.dpop.orchestrator.journey.strategy

import com.example.dpop.auth_device.AuthDeviceDescriptor
import com.example.dpop.auth_sms.AuthSmsUseDescriptor
import com.example.dpop.id_fsc.IdentFscDescriptor
import com.example.dpop.orchestrator.journey.Action
import com.example.dpop.orchestrator.journey.AuthIntent
import com.example.dpop.orchestrator.journey.JourneyEvent
import com.example.dpop.orchestrator.journey.Transition
import com.example.dpop.orchestrator.journey.state.FastAccessState
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
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Pure unit coverage of [FastAccessStrategy] - the fallback chain into a login, plus the mandatory
 * states that keep the next login working (docs/04-orchestrierung.md, "FAST_ACCESS"). No Spring
 * context, no HTTP.
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
        val state = FastAccessState.AuthChoice(listOf("auth-sms"))

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
        then("falls straight to identification") {
            val transition = strategy.transition(FastAccessState.Start, JourneyEvent.Started, ctx(account = null))
            transition.shouldBeInstanceOf<Transition.To>()
            (transition as Transition.To).state.shouldBeInstanceOf<FastAccessState.Identifying>()
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
                Transition.To(FastAccessState.AuthChoice(listOf("auth-sms")))
        }
    }

    given("Start, account known but has no active methods at all") {
        val acc = account()
        then("falls back to identification rather than a dead end") {
            val transition = strategy.transition(FastAccessState.Start, JourneyEvent.Started, ctx(account = acc))
            transition.shouldBeInstanceOf<Transition.To>()
            (transition as Transition.To).state.shouldBeInstanceOf<FastAccessState.Identifying>()
        }
    }

    given("Start, resumed after a RE_IDENTIFY sub-journey (SubJourneyFinished)") {
        val acc = account(method("sms", "loa1"))
        val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc), acrFloor = "loa1")
        then("re-checks satisfaction via afterProof instead of re-running firstOffer") {
            val event = JourneyEvent.SubJourneyFinished(AuthIntent.RE_IDENTIFY, achievedAcr = "loa2")
            strategy.transition(FastAccessState.Start, event, theCtx) shouldBe Transition.Authenticated
        }
    }

    given("Start, declined instead (SubJourneyCancelled)") {
        then("gives up on its own rather than re-requesting the identical RE_IDENTIFY again") {
            val event = JourneyEvent.SubJourneyCancelled(AuthIntent.RE_IDENTIFY)
            strategy.transition(FastAccessState.Start, event, ctx()) shouldBe Transition.Cancel
        }
    }

    given("PreferredAuth, declined") {
        val acc = account(method("device", "loa2", details = deviceDetails()), method("sms", "loa2"))
        then("falls back to the account's other methods") {
            strategy.transition(FastAccessState.PreferredAuth("auth-device"), JourneyEvent.Abandoned(AuthDeviceDescriptor), ctx(account = acc)) shouldBe
                Transition.To(FastAccessState.AuthChoice(listOf("auth-sms"), declined = emptySet()))
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
        val state = FastAccessState.AuthChoice(listOf("auth-sms", "auth-password"))
        val acc = account(method("sms", "loa2"), method("password", "loa2"))
        then("abandoning one keeps the run in AuthChoice with the rest still offered") {
            strategy.transition(state, JourneyEvent.Abandoned(AuthSmsUseDescriptor), ctx(account = acc)) shouldBe
                Transition.To(state.copy(declined = setOf("auth-sms"), active = null))
        }
    }

    given("AuthChoice, the last offered candidate is abandoned") {
        val acc = account(method("sms", "loa2"))
        then("falls through to identification, same as an account with nothing usable at all") {
            val transition = strategy.transition(FastAccessState.AuthChoice(listOf("auth-sms")), JourneyEvent.Abandoned(AuthSmsUseDescriptor), ctx(account = acc))
            transition.shouldBeInstanceOf<Transition.To>()
            (transition as Transition.To).state.shouldBeInstanceOf<FastAccessState.Identifying>()
        }
    }

    given("Identifying, more than one offered candidate") {
        val state = FastAccessState.Identifying(listOf("ident-fsc", "ident-eid"))
        then("abandoning one keeps the choice among the rest") {
            strategy.transition(state, JourneyEvent.Abandoned(IdentFscDescriptor), ctx()) shouldBe
                Transition.To(state.copy(declined = setOf("ident-fsc")))
        }
    }

    given("Identifying, the last offered candidate is abandoned") {
        then("gives up - the whole journey cancels, this is not an error") {
            strategy.transition(FastAccessState.Identifying(listOf("ident-fsc")), JourneyEvent.Abandoned(IdentFscDescriptor), ctx()) shouldBe Transition.Cancel
        }
    }

    given("Identifying, a fresh identity was just established") {
        `when`("the newly (re-)found account can already reach the floor with an existing method") {
            val acc = account(method("sms", "loa1"))
            val theCtx = ctx(account = acc, acrFloor = "loa1")
            val state = FastAccessState.Identifying(listOf("ident-fsc"))
            then("adopts the identity, then offers it, rather than enrollment") {
                val outcome = ToolOutcome.Completed.Identified(personId = 1L)
                val event = JourneyEvent.Completed(IdentFscDescriptor, outcome)
                strategy.transition(state, event, theCtx) shouldBe
                    Transition.Perform(Action.AdoptIdentity(IdentFscDescriptor, outcome), resumeState = state)
                strategy.transition(state, JourneyEvent.ActionCompleted, theCtx) shouldBe
                    Transition.To(FastAccessState.AuthChoice(listOf("auth-sms")))
            }
        }

        `when`("the account (brand new, or found without a sufficient method) needs to enroll something") {
            val acc = account(emailConfirmed = false)
            val theCtx = ctx(account = acc, acrFloor = "loa1")
            val state = FastAccessState.Identifying(listOf("ident-fsc"))
            then("adopts the identity, then offers enrollment, carrying the email obligation this run incurred") {
                val outcome = ToolOutcome.Completed.Identified(personId = 1L)
                val event = JourneyEvent.Completed(IdentFscDescriptor, outcome)
                strategy.transition(state, event, theCtx) shouldBe
                    Transition.Perform(Action.AdoptIdentity(IdentFscDescriptor, outcome), resumeState = state)

                val transition = strategy.transition(state, JourneyEvent.ActionCompleted, theCtx)
                transition.shouldBeInstanceOf<Transition.To>()
                val to = (transition as Transition.To).state
                to.shouldBeInstanceOf<FastAccessState.Enrolling>()
                to as FastAccessState.Enrolling
                to.emailObligation shouldBe true
                to.offered shouldContainExactlyInAnyOrder listOf("enroll-sms", "enroll-email", "enroll-device")
            }
        }
    }

    given("ConfirmingEmail") {
        val state = FastAccessState.ConfirmingEmail(listOf("enroll-email"))

        `when`("abandoned") {
            then("re-offers the same full choice - the obligation itself is never waived by backing out") {
                strategy.transition(state, JourneyEvent.Abandoned(com.example.dpop.auth_email.EnrollEmailDescriptor), ctx()) shouldBe
                    Transition.To(state.withActive(null))
            }
        }

        `when`("the email is confirmed, and the account now reaches the floor") {
            val acc = account(method("sms", "loa1"), emailConfirmed = true)
            val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc), acrFloor = "loa1")
            then("adopts the credential, then finishes - the obligation is discharged, nothing else to enroll") {
                val outcome = ToolOutcome.Completed.Enrolled(enrollmentRef = EnrollmentRef("email", "ref"))
                val event = JourneyEvent.Completed(com.example.dpop.auth_email.EnrollEmailDescriptor, outcome)
                strategy.transition(state, event, theCtx) shouldBe
                    Transition.Perform(Action.AdoptCredential(com.example.dpop.auth_email.EnrollEmailDescriptor, outcome, bindDevice = true), resumeState = state)
                strategy.transition(state, JourneyEvent.ActionCompleted, theCtx) shouldBe Transition.Authenticated
            }
        }
    }

    given("Enrolling") {
        `when`("abandoned") {
            val state = FastAccessState.Enrolling(listOf("enroll-sms", "enroll-password"), emailObligation = true)
            then("re-offers the same full choice, the tool just backed out of included") {
                strategy.transition(state, JourneyEvent.Abandoned(AuthSmsUseDescriptor), ctx()) shouldBe Transition.To(state.withActive(null))
            }
        }

        `when`("a method was just enrolled and the floor is now reached, with no email obligation") {
            val acc = account(method("sms", "loa1"))
            val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc), acrFloor = "loa1")
            val state = FastAccessState.Enrolling(listOf("enroll-sms"), emailObligation = false)
            then("adopts the credential, then finishes directly") {
                val outcome = ToolOutcome.Completed.Enrolled(enrollmentRef = EnrollmentRef("sms", "ref"))
                val event = JourneyEvent.Completed(AuthSmsUseDescriptor, outcome)
                strategy.transition(state, event, theCtx) shouldBe
                    Transition.Perform(Action.AdoptCredential(AuthSmsUseDescriptor, outcome, bindDevice = true), resumeState = state)
                strategy.transition(state, JourneyEvent.ActionCompleted, theCtx) shouldBe Transition.Authenticated
            }
        }

        `when`("a method was just enrolled, floor reached, but the email obligation from Identifying is still open") {
            val acc = account(method("sms", "loa1"), emailConfirmed = false)
            val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc), acrFloor = "loa1")
            val state = FastAccessState.Enrolling(listOf("enroll-sms"), emailObligation = true)
            then("adopts the credential, then moves on to ConfirmingEmail instead of finishing") {
                val outcome = ToolOutcome.Completed.Enrolled(enrollmentRef = EnrollmentRef("sms", "ref"))
                val event = JourneyEvent.Completed(AuthSmsUseDescriptor, outcome)
                strategy.transition(state, event, theCtx) shouldBe
                    Transition.Perform(Action.AdoptCredential(AuthSmsUseDescriptor, outcome, bindDevice = true), resumeState = state)
                strategy.transition(state, JourneyEvent.ActionCompleted, theCtx) shouldBe
                    Transition.To(FastAccessState.ConfirmingEmail(listOf("enroll-email")))
            }
        }
    }

    given("offerEnrollment's own dead end: no enrollment tool left at all (all backend-disabled)") {
        val acc = account(method("sms", "loa2"))
        val onlyAuthTools = setOf("auth-sms")
        val state = FastAccessState.AuthChoice(listOf("auth-sms"))

        `when`("re-identification could still close the gap") {
            val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc), acrFloor = "loa2", availableTools = onlyAuthTools + setOf("ident-fsc", "ident-eid"))
            then("accepts the proof, then requires the shared RE_IDENTIFY sub-journey instead of aborting") {
                val outcome = ToolOutcome.Completed.Authenticated(amr = listOf("sms"))
                val event = JourneyEvent.Completed(AuthSmsUseDescriptor, outcome)
                strategy.transition(state, event, theCtx) shouldBe
                    Transition.Perform(Action.AcceptProof(AuthSmsUseDescriptor, outcome, useOutcomeAccount = false, bindDevice = true), resumeState = state)
                strategy.transition(state, JourneyEvent.ActionCompleted, theCtx) shouldBe
                    Transition.RequireSubJourney(AuthIntent.RE_IDENTIFY, "loa2", resumeWith = FastAccessState.Start)
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
            strategy.cancelledTo(FastAccessState.Identifying(listOf("ident-fsc"))) shouldBe ChannelState.ANONYMOUS
        }
    }
})
