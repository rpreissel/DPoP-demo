package com.example.dpop.orchestrator.journey.strategy

import com.example.dpop.auth_device.AuthDeviceDescriptor
import com.example.dpop.auth_sms.AuthSmsUseDescriptor
import com.example.dpop.id_fsc.IdentFscDescriptor
import com.example.dpop.orchestrator.journey.AuthIntent
import com.example.dpop.orchestrator.journey.Decision
import com.example.dpop.orchestrator.journey.Effect
import com.example.dpop.orchestrator.journey.JourneyEvent
import com.example.dpop.orchestrator.journey.state.FastAccessState
import com.example.dpop.orchestrator.journey.strategy.StrategyTestFixtures.account
import com.example.dpop.orchestrator.journey.strategy.StrategyTestFixtures.ctx
import com.example.dpop.orchestrator.journey.strategy.StrategyTestFixtures.deviceDetails
import com.example.dpop.orchestrator.journey.strategy.StrategyTestFixtures.method
import com.example.dpop.orchestrator.policy.AuthEvidence
import com.example.dpop.orchestrator.session.ChannelState
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
 */
class FastAccessStrategyTest : BehaviorSpec({

    val strategy = FastAccessStrategy()

    given("the intent") {
        then("is FAST_ACCESS") {
            strategy.intent shouldBe AuthIntent.FAST_ACCESS
        }
    }

    given("interpret") {
        val state = FastAccessState.Start

        then("Identified always finds-or-creates the account - brand new or found again by KVNR alike") {
            strategy.interpret(state, IdentFscDescriptor, ToolOutcome.Completed.Identified(personId = 1L)) shouldBe Effect.AdoptIdentity
        }

        then("Enrolled binds the device - a fresh credential on this device is worth remembering") {
            strategy.interpret(state, AuthSmsUseDescriptor, ToolOutcome.Completed.Enrolled(enrollmentRef = com.example.dpop.tool_spi.EnrollmentRef("sms", "ref"))) shouldBe
                Effect.AdoptCredential(bindDevice = true)
        }

        then("Authenticated is accepted as proof, never adopting a different account, always binding the device") {
            strategy.interpret(state, AuthSmsUseDescriptor, ToolOutcome.Completed.Authenticated(amr = listOf("sms"))) shouldBe
                Effect.AcceptProof(useOutcomeAccount = false, bindDevice = true)
        }
    }

    given("Start, no account known yet (unrecognized device)") {
        then("falls straight to identification") {
            val decision = strategy.decide(FastAccessState.Start, JourneyEvent.Started, ctx(account = null))
            decision.shouldBeInstanceOf<Decision.Advance>()
            (decision as Decision.Advance).to.shouldBeInstanceOf<FastAccessState.Identifying>()
        }
    }

    given("Start, account known via a linked device credential") {
        val acc = account(method("device", "loa2", details = deviceDetails()))
        then("suggests exactly that device credential, not a generic choice") {
            strategy.decide(FastAccessState.Start, JourneyEvent.Started, ctx(account = acc)) shouldBe
                Decision.Advance(FastAccessState.PreferredAuth("auth-device"))
        }
    }

    given("Start, account known but no preferred device - other methods available") {
        val acc = account(method("sms", "loa2"))
        then("offers them via AuthChoice") {
            strategy.decide(FastAccessState.Start, JourneyEvent.Started, ctx(account = acc)) shouldBe
                Decision.Advance(FastAccessState.AuthChoice(listOf("auth-sms")))
        }
    }

    given("Start, account known but has no active methods at all") {
        val acc = account()
        then("falls back to identification rather than a dead end") {
            val decision = strategy.decide(FastAccessState.Start, JourneyEvent.Started, ctx(account = acc))
            decision.shouldBeInstanceOf<Decision.Advance>()
            (decision as Decision.Advance).to.shouldBeInstanceOf<FastAccessState.Identifying>()
        }
    }

    given("Start, resumed after a RE_IDENTIFY sub-journey (SubJourneyFinished)") {
        val acc = account(method("sms", "loa1"))
        val theCtx = ctx(account = acc, evidence = AuthEvidence(listOf("sms"), setOf(FactorType.POSSESSION)), acrFloor = "loa1")
        then("re-checks satisfaction via afterProof instead of re-running firstOffer") {
            val event = JourneyEvent.SubJourneyFinished(AuthIntent.RE_IDENTIFY, achievedAcr = "loa2")
            strategy.decide(FastAccessState.Start, event, theCtx) shouldBe Decision.Authenticated
        }
    }

    given("Start, declined instead (SubJourneyCancelled)") {
        then("gives up on its own rather than re-requesting the identical RE_IDENTIFY again") {
            val event = JourneyEvent.SubJourneyCancelled(AuthIntent.RE_IDENTIFY)
            strategy.decide(FastAccessState.Start, event, ctx()) shouldBe Decision.Cancel
        }
    }

    given("PreferredAuth, declined") {
        val acc = account(method("device", "loa2", details = deviceDetails()), method("sms", "loa2"))
        then("falls back to the account's other methods") {
            strategy.decide(FastAccessState.PreferredAuth("auth-device"), JourneyEvent.Abandoned(AuthDeviceDescriptor), ctx(account = acc)) shouldBe
                Decision.Advance(FastAccessState.AuthChoice(listOf("auth-sms"), declined = emptySet()))
        }
    }

    given("PreferredAuth, a proof just completed and it already satisfies the floor") {
        val acc = account(method("device", "loa2", details = deviceDetails()))
        val theCtx = ctx(account = acc, evidence = AuthEvidence(listOf("device"), setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE, FactorType.INHERENCE)), acrFloor = "loa2")
        then("finishes") {
            val event = JourneyEvent.Completed(AuthDeviceDescriptor, ToolOutcome.Completed.Authenticated(amr = listOf("device")))
            strategy.decide(FastAccessState.PreferredAuth("auth-device"), event, theCtx) shouldBe Decision.Authenticated
        }
    }

    given("AuthChoice, more than one offered candidate") {
        val state = FastAccessState.AuthChoice(listOf("auth-sms", "auth-password"))
        val acc = account(method("sms", "loa2"), method("password", "loa2"))
        then("abandoning one keeps the run in AuthChoice with the rest still offered") {
            strategy.decide(state, JourneyEvent.Abandoned(AuthSmsUseDescriptor), ctx(account = acc)) shouldBe
                Decision.Advance(state.copy(declined = setOf("auth-sms"), active = null))
        }
    }

    given("AuthChoice, the last offered candidate is abandoned") {
        val acc = account(method("sms", "loa2"))
        then("falls through to identification, same as an account with nothing usable at all") {
            val decision = strategy.decide(FastAccessState.AuthChoice(listOf("auth-sms")), JourneyEvent.Abandoned(AuthSmsUseDescriptor), ctx(account = acc))
            decision.shouldBeInstanceOf<Decision.Advance>()
            (decision as Decision.Advance).to.shouldBeInstanceOf<FastAccessState.Identifying>()
        }
    }

    given("Identifying, more than one offered candidate") {
        val state = FastAccessState.Identifying(listOf("ident-fsc", "ident-eid"))
        then("abandoning one keeps the choice among the rest") {
            strategy.decide(state, JourneyEvent.Abandoned(IdentFscDescriptor), ctx()) shouldBe
                Decision.Advance(state.copy(declined = setOf("ident-fsc")))
        }
    }

    given("Identifying, the last offered candidate is abandoned") {
        then("gives up - the whole journey cancels, this is not an error") {
            strategy.decide(FastAccessState.Identifying(listOf("ident-fsc")), JourneyEvent.Abandoned(IdentFscDescriptor), ctx()) shouldBe Decision.Cancel
        }
    }

    given("Identifying, a fresh identity was just established") {
        `when`("the newly (re-)found account can already reach the floor with an existing method") {
            val acc = account(method("sms", "loa1"))
            val theCtx = ctx(account = acc, acrFloor = "loa1")
            then("offers it, rather than enrollment") {
                val event = JourneyEvent.Completed(IdentFscDescriptor, ToolOutcome.Completed.Identified(personId = 1L))
                strategy.decide(FastAccessState.Identifying(listOf("ident-fsc")), event, theCtx) shouldBe
                    Decision.Advance(FastAccessState.AuthChoice(listOf("auth-sms")))
            }
        }

        `when`("the account (brand new, or found without a sufficient method) needs to enroll something") {
            val acc = account(emailConfirmed = false)
            val theCtx = ctx(account = acc, acrFloor = "loa1")
            then("offers enrollment, carrying the email obligation this run incurred") {
                val event = JourneyEvent.Completed(IdentFscDescriptor, ToolOutcome.Completed.Identified(personId = 1L))
                val decision = strategy.decide(FastAccessState.Identifying(listOf("ident-fsc")), event, theCtx)
                decision.shouldBeInstanceOf<Decision.Advance>()
                val to = (decision as Decision.Advance).to
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
                strategy.decide(state, JourneyEvent.Abandoned(com.example.dpop.auth_email.EnrollEmailDescriptor), ctx()) shouldBe
                    Decision.Advance(state.withActive(null))
            }
        }

        `when`("the email is confirmed, and the account now reaches the floor") {
            val acc = account(method("sms", "loa1"), emailConfirmed = true)
            val theCtx = ctx(account = acc, evidence = AuthEvidence(listOf("sms"), setOf(FactorType.POSSESSION)), acrFloor = "loa1")
            then("finishes - the obligation is discharged, nothing else to enroll") {
                val event = JourneyEvent.Completed(com.example.dpop.auth_email.EnrollEmailDescriptor, ToolOutcome.Completed.Enrolled(enrollmentRef = com.example.dpop.tool_spi.EnrollmentRef("email", "ref")))
                strategy.decide(state, event, theCtx) shouldBe Decision.Authenticated
            }
        }
    }

    given("Enrolling") {
        `when`("abandoned") {
            val state = FastAccessState.Enrolling(listOf("enroll-sms", "enroll-password"), emailObligation = true)
            then("re-offers the same full choice, the tool just backed out of included") {
                strategy.decide(state, JourneyEvent.Abandoned(AuthSmsUseDescriptor), ctx()) shouldBe Decision.Advance(state.withActive(null))
            }
        }

        `when`("a method was just enrolled and the floor is now reached, with no email obligation") {
            val acc = account(method("sms", "loa1"))
            val theCtx = ctx(account = acc, evidence = AuthEvidence(listOf("sms"), setOf(FactorType.POSSESSION)), acrFloor = "loa1")
            val state = FastAccessState.Enrolling(listOf("enroll-sms"), emailObligation = false)
            then("finishes directly") {
                val event = JourneyEvent.Completed(AuthSmsUseDescriptor, ToolOutcome.Completed.Enrolled(enrollmentRef = com.example.dpop.tool_spi.EnrollmentRef("sms", "ref")))
                strategy.decide(state, event, theCtx) shouldBe Decision.Authenticated
            }
        }

        `when`("a method was just enrolled, floor reached, but the email obligation from Identifying is still open") {
            val acc = account(method("sms", "loa1"), emailConfirmed = false)
            val theCtx = ctx(account = acc, evidence = AuthEvidence(listOf("sms"), setOf(FactorType.POSSESSION)), acrFloor = "loa1")
            val state = FastAccessState.Enrolling(listOf("enroll-sms"), emailObligation = true)
            then("moves on to ConfirmingEmail instead of finishing") {
                val event = JourneyEvent.Completed(AuthSmsUseDescriptor, ToolOutcome.Completed.Enrolled(enrollmentRef = com.example.dpop.tool_spi.EnrollmentRef("sms", "ref")))
                strategy.decide(state, event, theCtx) shouldBe Decision.Advance(FastAccessState.ConfirmingEmail(listOf("enroll-email")))
            }
        }
    }

    given("offerEnrollment's own dead end: no enrollment tool left at all (all backend-disabled)") {
        val acc = account(method("sms", "loa2"))
        val onlyAuthTools = setOf("auth-sms")

        `when`("re-identification could still close the gap") {
            val theCtx = ctx(account = acc, evidence = AuthEvidence(listOf("sms"), setOf(FactorType.POSSESSION)), acrFloor = "loa2", availableTools = onlyAuthTools + setOf("ident-fsc", "ident-eid"))
            then("requires the shared RE_IDENTIFY sub-journey instead of aborting") {
                val event = JourneyEvent.Completed(AuthSmsUseDescriptor, ToolOutcome.Completed.Authenticated(amr = listOf("sms")))
                strategy.decide(FastAccessState.AuthChoice(listOf("auth-sms")), event, theCtx) shouldBe
                    Decision.RequireSubJourney(AuthIntent.RE_IDENTIFY, "loa2", resumeWith = FastAccessState.Start)
            }
        }

        `when`("nothing can help at all") {
            val theCtx = ctx(account = acc, evidence = AuthEvidence(listOf("sms"), setOf(FactorType.POSSESSION)), acrFloor = "loa2", availableTools = onlyAuthTools)
            then("aborts with a reason") {
                val event = JourneyEvent.Completed(AuthSmsUseDescriptor, ToolOutcome.Completed.Authenticated(amr = listOf("sms")))
                strategy.decide(FastAccessState.AuthChoice(listOf("auth-sms")), event, theCtx).shouldBeInstanceOf<Decision.Abort>()
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
