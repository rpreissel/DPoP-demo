package com.example.dpop.orchestrator.journey.strategy

import com.example.dpop.auth_sms.AuthSmsLookupDescriptor
import com.example.dpop.orchestrator.journey.AuthIntent
import com.example.dpop.orchestrator.journey.Decision
import com.example.dpop.orchestrator.journey.Interpretation
import com.example.dpop.orchestrator.journey.JourneyEvent
import com.example.dpop.orchestrator.journey.state.LookupLoginState
import com.example.dpop.orchestrator.journey.strategy.StrategyTestFixtures.account
import com.example.dpop.orchestrator.journey.strategy.StrategyTestFixtures.ctx
import com.example.dpop.orchestrator.journey.strategy.StrategyTestFixtures.method
import com.example.dpop.orchestrator.policy.AuthEvidence
import com.example.dpop.orchestrator.session.ChannelState
import com.example.dpop.tool_spi.FactorType
import com.example.dpop.tool_spi.ToolOutcome
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Pure unit coverage of [LookupLoginStrategy] - logging into an existing account from an unpaired
 * device (docs/04-orchestrierung.md, "LOOKUP_LOGIN"). No Spring context, no HTTP.
 */
class LookupLoginStrategyTest : BehaviorSpec({

    val strategy = LookupLoginStrategy()

    given("the intent") {
        then("is LOOKUP_LOGIN") {
            strategy.intent shouldBe AuthIntent.LOOKUP_LOGIN
        }
    }

    given("initialState") {
        then("is Start") {
            strategy.initialState(ctx()) shouldBe LookupLoginState.Start
        }
    }

    given("interpret") {
        then("on Credential (the first, account-resolving proof) trusts the tool's own account") {
            strategy.interpret(LookupLoginState.Credential(listOf("auth-sms-lookup")), AuthSmsLookupDescriptor, ToolOutcome.Completed.Authenticated(amr = listOf("sms"), accountId = 42L)) shouldBe
                Interpretation.AcceptProof(useOutcomeAccount = true, bindDevice = false)
        }

        then("on AdditionalFactor (any further proof) never trusts a submitted account") {
            strategy.interpret(LookupLoginState.AdditionalFactor(listOf("auth-sms")), AuthSmsLookupDescriptor, ToolOutcome.Completed.Authenticated(amr = listOf("sms"))) shouldBe
                Interpretation.AcceptProof(useOutcomeAccount = false, bindDevice = false)
        }

        then("Identified is not offered by any state of this intent") {
            shouldThrow<IllegalStateException> {
                strategy.interpret(LookupLoginState.Start, AuthSmsLookupDescriptor, ToolOutcome.Completed.Identified(personId = 1L))
            }
        }

        then("Enrolled is not offered by any state of this intent") {
            shouldThrow<IllegalStateException> {
                strategy.interpret(LookupLoginState.Start, AuthSmsLookupDescriptor, ToolOutcome.Completed.Enrolled(enrollmentRef = com.example.dpop.tool_spi.EnrollmentRef("sms", "ref")))
            }
        }
    }

    given("Start") {
        `when`("started, with lookup-capable tools available") {
            then("offers every LOOKUP_AUTH tool in the catalog") {
                val decision = strategy.next(LookupLoginState.Start, JourneyEvent.Started, ctx())
                decision.shouldBeInstanceOf<Decision.Advance>()
                val to = (decision as Decision.Advance).to
                to.shouldBeInstanceOf<LookupLoginState.Credential>()
                (to as LookupLoginState.Credential).offered shouldContainExactly listOf("auth-sms-lookup", "auth-email-lookup", "auth-password-lookup")
            }
        }

        `when`("started, but none of them are available client-side") {
            then("aborts - no login path without a paired device exists") {
                val decision = strategy.next(LookupLoginState.Start, JourneyEvent.Started, ctx(availableTools = emptySet()))
                decision.shouldBeInstanceOf<Decision.Abort>()
            }
        }

        `when`("resumed after a RE_IDENTIFY sub-journey (SubJourneyFinished)") {
            val acc = account(method("sms", "loa1"))
            val theCtx = ctx(account = acc, evidence = AuthEvidence(listOf("sms"), setOf(FactorType.POSSESSION)))
            then("delegates to the same settle-or-raise check as any other proof") {
                val event = JourneyEvent.SubJourneyFinished(AuthIntent.RE_IDENTIFY, achievedAcr = "loa2")
                strategy.next(LookupLoginState.Start, event, theCtx) shouldBe Decision.Advance(LookupLoginState.OfferBinding(acc.accountId))
            }
        }

        `when`("it was declined instead (SubJourneyCancelled)") {
            then("gives up on its own rather than re-requesting the identical RE_IDENTIFY again") {
                val event = JourneyEvent.SubJourneyCancelled(AuthIntent.RE_IDENTIFY)
                strategy.next(LookupLoginState.Start, event, ctx()) shouldBe Decision.Cancel
            }
        }
    }

    given("Credential, more than one offered candidate") {
        val state = LookupLoginState.Credential(listOf("auth-sms-lookup", "auth-email-lookup"))

        `when`("one is abandoned, another remains") {
            then("advances, marking only that one declined") {
                strategy.next(state, JourneyEvent.Abandoned(AuthSmsLookupDescriptor), ctx()) shouldBe
                    Decision.Advance(state.copy(declined = setOf("auth-sms-lookup"), active = null))
            }
        }
    }

    given("Credential, the last offered candidate is abandoned") {
        val state = LookupLoginState.Credential(listOf("auth-sms-lookup"))
        then("cancels - giving up on the very first proof is not an error") {
            strategy.next(state, JourneyEvent.Abandoned(AuthSmsLookupDescriptor), ctx()) shouldBe Decision.Cancel
        }
    }

    given("Credential/AdditionalFactor, a proof just completed (settleOrRaise)") {
        `when`("the floor is already satisfied") {
            val acc = account(method("sms", "loa1"))
            val theCtx = ctx(account = acc, evidence = AuthEvidence(listOf("sms"), setOf(FactorType.POSSESSION)), acrFloor = "loa1")
            then("offers the optional device-binding prompt") {
                strategy.next(LookupLoginState.Credential(listOf("auth-sms-lookup")), JourneyEvent.Completed(AuthSmsLookupDescriptor, ToolOutcome.Completed.Authenticated(amr = listOf("sms"), accountId = acc.accountId)), theCtx) shouldBe
                    Decision.Advance(LookupLoginState.OfferBinding(acc.accountId))
            }
        }

        `when`("the floor is not yet satisfied, but another active method can help") {
            val acc = account(method("sms", "loa2"), method("password", "loa2"))
            val theCtx = ctx(account = acc, evidence = AuthEvidence(listOf("sms"), setOf(FactorType.POSSESSION)), acrFloor = "loa2")
            then("offers it via AdditionalFactor") {
                strategy.next(LookupLoginState.Credential(listOf("auth-sms-lookup")), JourneyEvent.Completed(AuthSmsLookupDescriptor, ToolOutcome.Completed.Authenticated(amr = listOf("sms"))), theCtx) shouldBe
                    Decision.Advance(LookupLoginState.AdditionalFactor(listOf("auth-password")))
            }
        }

        `when`("nothing active can help, but re-identification could") {
            val acc = account(method("sms", "loa2"))
            val theCtx = ctx(account = acc, evidence = AuthEvidence(listOf("sms"), setOf(FactorType.POSSESSION)), acrFloor = "loa2")
            then("requires the shared RE_IDENTIFY sub-journey - it only re-confirms this account, never adopts a different one") {
                strategy.next(LookupLoginState.Credential(listOf("auth-sms-lookup")), JourneyEvent.Completed(AuthSmsLookupDescriptor, ToolOutcome.Completed.Authenticated(amr = listOf("sms"))), theCtx) shouldBe
                    Decision.RequireSubJourney(AuthIntent.RE_IDENTIFY, "loa2", resumeWith = LookupLoginState.Start)
            }
        }

        `when`("nothing can help at all, not even re-identification (backend-disabled)") {
            val acc = account(method("sms", "loa2"))
            val theCtx = ctx(
                account = acc,
                evidence = AuthEvidence(listOf("sms"), setOf(FactorType.POSSESSION)),
                acrFloor = "loa2",
                availableTools = StrategyTestFixtures.allToolIds - setOf("ident-fsc", "ident-eid")
            )
            then("aborts with a reason - never a silent enrollment fallback (this intent has none)") {
                val decision = strategy.next(LookupLoginState.Credential(listOf("auth-sms-lookup")), JourneyEvent.Completed(AuthSmsLookupDescriptor, ToolOutcome.Completed.Authenticated(amr = listOf("sms"))), theCtx)
                decision.shouldBeInstanceOf<Decision.Abort>()
                (decision as Decision.Abort).reason shouldContain "nicht erreichbar"
            }
        }
    }

    given("OfferBinding") {
        val state = LookupLoginState.OfferBinding(accountId = 7L)

        then("accepting links the device, then finishes") {
            strategy.next(state, JourneyEvent.Answered("accept"), ctx()) shouldBe Decision.FinishWithDeviceLink(7L)
        }

        then("declining finishes without linking") {
            strategy.next(state, JourneyEvent.Answered("decline"), ctx()) shouldBe Decision.Finish
        }

        then("an unrecognized answer fails loudly") {
            shouldThrow<IllegalStateException> { strategy.next(state, JourneyEvent.Answered("maybe"), ctx()) }
        }

        then("any non-Answered event is rejected - this state never runs a tool") {
            shouldThrow<IllegalStateException> { strategy.next(state, JourneyEvent.Started, ctx()) }
        }
    }

    given("onCancel") {
        then("always falls back to ANONYMOUS - this intent never carries a durable account binding of its own") {
            strategy.onCancel(LookupLoginState.Start) shouldBe ChannelState.ANONYMOUS
            strategy.onCancel(LookupLoginState.Credential(listOf("auth-sms-lookup"))) shouldBe ChannelState.ANONYMOUS
        }
    }
})
