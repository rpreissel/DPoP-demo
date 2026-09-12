package com.example.dpop.orchestrator.journey.strategy

import com.example.dpop.auth_password.EnrollPasswordDescriptor
import com.example.dpop.auth_sms.AuthSmsUseDescriptor
import com.example.dpop.id_fsc.IdentFscDescriptor
import com.example.dpop.orchestrator.journey.Action
import com.example.dpop.orchestrator.journey.AuthIntent
import com.example.dpop.orchestrator.journey.JourneyEvent
import com.example.dpop.orchestrator.journey.Transition
import com.example.dpop.orchestrator.journey.state.AuthChoice
import com.example.dpop.orchestrator.journey.state.Enrolling
import com.example.dpop.orchestrator.journey.state.RegisterState
import com.example.dpop.orchestrator.journey.strategy.StrategyTestFixtures.account
import com.example.dpop.orchestrator.journey.strategy.StrategyTestFixtures.ctx
import com.example.dpop.orchestrator.journey.strategy.StrategyTestFixtures.evidence
import com.example.dpop.orchestrator.journey.strategy.StrategyTestFixtures.method
import com.example.dpop.orchestrator.session.ChannelSession
import com.example.dpop.tool_spi.EnrollmentRef
import com.example.dpop.tool_spi.FactorType
import com.example.dpop.tool_spi.ToolOutcome
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Pure unit coverage of [RegisterStrategy]'s own journey - deliberately fresh identification, even
 * on an already linked device (docs/04-orchestrierung.md, "REGISTER"), through to the mandatory
 * states that keep the next login working. [AuthChoice]/[Enrolling] are shared value types with
 * [FastAccessStrategy] (see [FastAccessCore]), but the transitions around them are still exercised
 * here in full, because this strategy's own `transition` function owns them just as much as
 * FastAccessStrategy's does - see [FastAccessStrategyTest] for FAST_ACCESS's own, narrower slice.
 */
class RegisterStrategyTest : BehaviorSpec({

    val strategy = RegisterStrategy()

    given("the intent") {
        then("is REGISTER") {
            strategy.intent shouldBe AuthIntent.REGISTER
        }
    }

    given("Start, even with a linked device credential and other active methods") {
        val acc = account(method("sms", "loa2"))

        then("always goes straight to identification - no PreferredAuth/AuthChoice shortcut exists here") {
            strategy.transition(RegisterState.Start, JourneyEvent.Started, ctx(account = acc)) shouldBe
                Transition.To(RegisterState.Identifying(listOf("ident-fsc", "ident-eid")))
        }
    }

    given("Start, no account known yet") {
        then("goes to identification, same as a run seeded by FAST_ACCESS's own sub-journey hand-off") {
            strategy.transition(RegisterState.Start, JourneyEvent.Started, ctx(account = null)) shouldBe
                Transition.To(RegisterState.Identifying(listOf("ident-fsc", "ident-eid")))
        }
    }

    given("Start, resumed after a RE_IDENTIFY sub-journey (SubJourneyFinished)") {
        val acc = account(method("sms", "loa1"))
        val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc), acrFloor = "loa1")
        then("re-checks satisfaction via afterProof instead of re-running identification") {
            val event = JourneyEvent.SubJourneyFinished(AuthIntent.RE_IDENTIFY, achievedAcr = "loa2")
            strategy.transition(RegisterState.Start, event, theCtx) shouldBe Transition.Authenticated
        }
    }

    given("Start, declined instead (SubJourneyCancelled)") {
        then("gives up on its own rather than re-requesting the identical RE_IDENTIFY again") {
            val event = JourneyEvent.SubJourneyCancelled(AuthIntent.RE_IDENTIFY)
            strategy.transition(RegisterState.Start, event, ctx()) shouldBe Transition.Cancel
        }
    }

    given("Identifying, more than one offered candidate") {
        val state = RegisterState.Identifying(listOf("ident-fsc", "ident-eid"))
        then("abandoning one keeps the choice among the rest") {
            strategy.transition(state, JourneyEvent.Abandoned(IdentFscDescriptor), ctx()) shouldBe
                Transition.To(state.copy(declined = setOf("ident-fsc")))
        }
    }

    given("Identifying, the last offered candidate is abandoned") {
        then("gives up - the whole journey cancels, this is not an error") {
            strategy.transition(RegisterState.Identifying(listOf("ident-fsc")), JourneyEvent.Abandoned(IdentFscDescriptor), ctx()) shouldBe Transition.Cancel
        }
    }

    given("Identifying, a fresh identity was just established") {
        `when`("the newly (re-)found account can already reach the floor with an existing method") {
            val acc = account(method("sms", "loa1"))
            val theCtx = ctx(account = acc, acrFloor = "loa1")
            val state = RegisterState.Identifying(listOf("ident-fsc"))
            then("adopts the identity, then offers it via the shared AuthChoice, rather than enrollment") {
                val outcome = ToolOutcome.Completed.Identified(personId = 1L)
                val event = JourneyEvent.Completed(IdentFscDescriptor, outcome)
                strategy.transition(state, event, theCtx) shouldBe
                    Transition.Perform(Action.AdoptIdentity(IdentFscDescriptor, outcome), resumeState = state)
                strategy.transition(state, JourneyEvent.ActionCompleted, theCtx) shouldBe
                    Transition.To(AuthChoice(listOf("auth-sms")))
            }
        }

        `when`("the account (brand new, or found without a sufficient method) needs to enroll something") {
            val acc = account(emailConfirmed = false)
            val theCtx = ctx(account = acc, acrFloor = "loa1")
            val state = RegisterState.Identifying(listOf("ident-fsc"))
            then("adopts the identity, then offers enrollment, carrying the email obligation this run incurred") {
                val outcome = ToolOutcome.Completed.Identified(personId = 1L)
                val event = JourneyEvent.Completed(IdentFscDescriptor, outcome)
                strategy.transition(state, event, theCtx) shouldBe
                    Transition.Perform(Action.AdoptIdentity(IdentFscDescriptor, outcome), resumeState = state)

                val transition = strategy.transition(state, JourneyEvent.ActionCompleted, theCtx)
                transition.shouldBeInstanceOf<Transition.To>()
                val to = (transition as Transition.To).state
                to.shouldBeInstanceOf<Enrolling>()
                to as Enrolling
                to.emailObligation shouldBe true
                to.offered shouldContainExactlyInAnyOrder listOf("enroll-sms", "enroll-email", "enroll-device", "enroll-qr")
            }
        }
    }

    given("AuthChoice, reached after identification rediscovers an already-equipped account, last candidate abandoned") {
        val acc = account(method("sms", "loa2"))
        then("falls back to identification again, not to enrollment - never wrapped with the password obligation") {
            val transition = strategy.transition(
                AuthChoice(listOf("auth-sms")),
                JourneyEvent.Abandoned(AuthSmsUseDescriptor),
                ctx(account = acc, channel = ChannelSession.Channel.KEYCLOAK)
            )
            transition shouldBe Transition.To(RegisterState.Identifying(listOf("ident-fsc", "ident-eid")))
        }
    }

    given("ConfirmingEmail") {
        val state = RegisterState.ConfirmingEmail(listOf("enroll-email"))

        `when`("abandoned") {
            then("re-offers the same full choice - the obligation itself is never waived by backing out") {
                strategy.transition(state, JourneyEvent.Abandoned(com.example.dpop.auth_email.EnrollEmailDescriptor), ctx()) shouldBe
                    Transition.To(state.withActive(null))
            }
        }

        `when`("the email is confirmed, and the account now reaches the floor, on the APP channel") {
            val acc = account(method("sms", "loa1"), emailConfirmed = true)
            val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc), acrFloor = "loa1", channel = ChannelSession.Channel.APP)
            then("adopts the credential, then finishes - the obligation is discharged, no password obligation on APP") {
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
            val state = Enrolling(listOf("enroll-sms", "enroll-password"), emailObligation = true)
            then("re-offers the same full choice, the tool just backed out of included") {
                strategy.transition(state, JourneyEvent.Abandoned(AuthSmsUseDescriptor), ctx()) shouldBe Transition.To(state.withActive(null))
            }
        }

        `when`("a method was just enrolled, floor reached, but the email obligation from Identifying is still open") {
            val acc = account(method("sms", "loa1"), emailConfirmed = false)
            val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc), acrFloor = "loa1")
            val state = Enrolling(listOf("enroll-sms"), emailObligation = true)
            then("adopts the credential, then moves on to ConfirmingEmail instead of finishing") {
                val outcome = ToolOutcome.Completed.Enrolled(enrollmentRef = EnrollmentRef("sms", "ref"))
                val event = JourneyEvent.Completed(AuthSmsUseDescriptor, outcome)
                strategy.transition(state, event, theCtx) shouldBe
                    Transition.Perform(Action.AdoptCredential(AuthSmsUseDescriptor, outcome, bindDevice = true), resumeState = state)
                strategy.transition(state, JourneyEvent.ActionCompleted, theCtx) shouldBe
                    Transition.To(RegisterState.ConfirmingEmail(listOf("enroll-email")))
            }
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
        val state = Enrolling(listOf("enroll-sms", "enroll-password"), emailObligation = false)

        then("adopts the credential, then moves on to PasswordObligation instead of finishing") {
            val outcome = ToolOutcome.Completed.Enrolled(enrollmentRef = EnrollmentRef("sms", "ref"))
            val event = JourneyEvent.Completed(AuthSmsUseDescriptor, outcome)
            strategy.transition(state, event, theCtx) shouldBe
                Transition.Perform(Action.AdoptCredential(AuthSmsUseDescriptor, outcome, bindDevice = true), resumeState = state)
            strategy.transition(state, JourneyEvent.ActionCompleted, theCtx) shouldBe
                Transition.To(RegisterState.PasswordObligation(listOf("enroll-password")))
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
        val state = Enrolling(listOf("enroll-sms"), emailObligation = true)

        then("ConfirmingEmail comes first - password is never even considered until the email is confirmed") {
            val outcome = ToolOutcome.Completed.Enrolled(enrollmentRef = EnrollmentRef("sms", "ref"))
            val event = JourneyEvent.Completed(AuthSmsUseDescriptor, outcome)
            strategy.transition(state, event, theCtx) shouldBe
                Transition.Perform(Action.AdoptCredential(AuthSmsUseDescriptor, outcome, bindDevice = true), resumeState = state)
            strategy.transition(state, JourneyEvent.ActionCompleted, theCtx) shouldBe
                Transition.To(RegisterState.ConfirmingEmail(listOf("enroll-email")))
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
        val state = Enrolling(listOf("enroll-sms", "enroll-password"), emailObligation = false)

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
        val state = Enrolling(listOf("enroll-sms", "enroll-password"), emailObligation = false)

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
        val state = Enrolling(listOf("enroll-sms"), emailObligation = false)

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
        val state = RegisterState.ConfirmingEmail(listOf("enroll-email"))

        then("the discharged email obligation falls through to the still-open password obligation, not straight to Authenticated") {
            val outcome = ToolOutcome.Completed.Enrolled(enrollmentRef = EnrollmentRef("email", "ref"))
            val event = JourneyEvent.Completed(com.example.dpop.auth_email.EnrollEmailDescriptor, outcome)
            strategy.transition(state, event, theCtx) shouldBe
                Transition.Perform(Action.AdoptCredential(com.example.dpop.auth_email.EnrollEmailDescriptor, outcome, bindDevice = true), resumeState = state)
            strategy.transition(state, JourneyEvent.ActionCompleted, theCtx) shouldBe
                Transition.To(RegisterState.PasswordObligation(listOf("enroll-password")))
        }
    }

    given("PasswordObligation") {
        `when`("abandoned") {
            val state = RegisterState.PasswordObligation(listOf("enroll-password"))
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
            val state = RegisterState.PasswordObligation(listOf("enroll-password"))
            then("adopts the credential, then finishes - every obligation is now discharged") {
                val outcome = ToolOutcome.Completed.Enrolled(enrollmentRef = EnrollmentRef("password", "ref"))
                val event = JourneyEvent.Completed(EnrollPasswordDescriptor, outcome)
                strategy.transition(state, event, theCtx) shouldBe
                    Transition.Perform(Action.AdoptCredential(EnrollPasswordDescriptor, outcome, bindDevice = true), resumeState = state)
                strategy.transition(state, JourneyEvent.ActionCompleted, theCtx) shouldBe Transition.Authenticated
            }
        }
    }

    given("onCancel") {
        then("always falls back to ANONYMOUS") {
            strategy.cancelledTo(RegisterState.Start) shouldBe com.example.dpop.orchestrator.session.ChannelState.ANONYMOUS
            strategy.cancelledTo(RegisterState.Identifying(listOf("ident-fsc"))) shouldBe com.example.dpop.orchestrator.session.ChannelState.ANONYMOUS
        }
    }
})
