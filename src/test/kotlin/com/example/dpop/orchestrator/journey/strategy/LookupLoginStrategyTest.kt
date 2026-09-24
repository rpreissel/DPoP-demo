package com.example.dpop.orchestrator.journey.strategy

import com.example.dpop.auth_sms.AuthSmsLookupDescriptor
import com.example.dpop.orchestrator.journey.Action
import com.example.dpop.orchestrator.kernel.AuthIntent
import com.example.dpop.orchestrator.journey.JourneyEvent
import com.example.dpop.orchestrator.journey.Transition
import com.example.dpop.orchestrator.journey.state.Offer
import com.example.dpop.orchestrator.journey.state.LookupLoginState
import com.example.dpop.orchestrator.journey.state.ReIdentifyState
import com.example.dpop.orchestrator.journey.strategy.StrategyTestFixtures.account
import com.example.dpop.orchestrator.journey.strategy.StrategyTestFixtures.ctx
import com.example.dpop.orchestrator.journey.strategy.StrategyTestFixtures.method
import com.example.dpop.orchestrator.journey.strategy.StrategyTestFixtures.evidence
import com.example.dpop.orchestrator.session.ChannelState
import com.example.dpop.tool_spi.AcrLevel
import com.example.dpop.tool_spi.EnrollmentRef
import com.example.dpop.tool_spi.FactorType
import com.example.dpop.tool_spi.ToolId
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

    given("a completed tool") {
        then("on Credential (the first, account-resolving proof) trusts the tool's own account") {
            val state = LookupLoginState.Credential(Offer(listOf(ToolId("auth-sms-lookup"))))
            val outcome = ToolOutcome.Completed.Authenticated(amr = listOf("sms"), accountId = 42L)
            val event = JourneyEvent.Completed(AuthSmsLookupDescriptor, outcome)
            strategy.transition(state, event, ctx()) shouldBe
                Transition.Perform(Action.AcceptProof(AuthSmsLookupDescriptor, outcome), resumeState = state)
        }

        then("on AdditionalFactor (any further proof) never trusts a submitted account") {
            val state = LookupLoginState.AdditionalFactor(Offer(listOf(ToolId("auth-sms"))))
            val outcome = ToolOutcome.Completed.Authenticated(amr = listOf("sms"))
            val event = JourneyEvent.Completed(AuthSmsLookupDescriptor, outcome)
            strategy.transition(state, event, ctx()) shouldBe
                Transition.Perform(Action.AcceptProof(AuthSmsLookupDescriptor, outcome), resumeState = state)
        }

        then("Identified is not offered by any state of this intent") {
            shouldThrow<IllegalStateException> {
                strategy.transition(LookupLoginState.Credential(Offer(listOf(ToolId("auth-sms-lookup")))), JourneyEvent.Completed(AuthSmsLookupDescriptor, ToolOutcome.Completed.Identified(claims = listOf(com.example.dpop.tool_spi.Claim(com.example.dpop.tool_spi.AttributeType.PERSON_ID, "P000000001", com.example.dpop.tool_spi.ClaimSource.PERSON_DIRECTORY)))), ctx())
            }
        }

        then("Enrolled is not offered by any state of this intent") {
            shouldThrow<IllegalStateException> {
                strategy.transition(
                    LookupLoginState.Credential(Offer(listOf(ToolId("auth-sms-lookup")))),
                    JourneyEvent.Completed(AuthSmsLookupDescriptor, ToolOutcome.Completed.Enrolled(enrollmentRef = EnrollmentRef("sms", "ref"))),
                    ctx()
                )
            }
        }
    }

    given("Start") {
        `when`("started, with lookup-capable tools available") {
            then("offers every LOOKUP_AUTH tool in the catalog") {
                val transition = strategy.transition(LookupLoginState.Start, JourneyEvent.Started, ctx())
                transition.shouldBeInstanceOf<Transition.To>()
                val to = (transition as Transition.To).state
                to.shouldBeInstanceOf<LookupLoginState.Credential>()
                (to as LookupLoginState.Credential).offered shouldContainExactly listOf(ToolId("auth-sms-lookup"), ToolId("auth-email-lookup"), ToolId("auth-password-lookup"), ToolId("auth-qr-lookup"))
            }
        }

        `when`("started, but none of them are available client-side") {
            then("aborts - no login path without a paired device exists") {
                val transition = strategy.transition(LookupLoginState.Start, JourneyEvent.Started, ctx(availableTools = emptySet()))
                transition.shouldBeInstanceOf<Transition.Abort>()
            }
        }

        `when`("resumed after a RE_IDENTIFY sub-journey (SubJourneyFinished)") {
            val acc = account(method("sms", AcrLevel.LOA1))
            val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc))
            then("delegates to the same settle-or-raise check as any other proof") {
                val event = JourneyEvent.SubJourneyFinished(AuthIntent.RE_IDENTIFY, achievedAcr = AcrLevel.LOA2)
                strategy.transition(LookupLoginState.Start, event, theCtx) shouldBe Transition.To(LookupLoginState.OfferBinding)
            }
        }

        `when`("it was declined instead (SubJourneyCancelled)") {
            then("gives up on its own rather than re-requesting the identical RE_IDENTIFY again") {
                val event = JourneyEvent.SubJourneyCancelled(AuthIntent.RE_IDENTIFY)
                strategy.transition(LookupLoginState.Start, event, ctx()) shouldBe Transition.Cancel
            }
        }
    }

    given("Credential, more than one offered candidate") {
        val state = LookupLoginState.Credential(Offer(listOf(ToolId("auth-sms-lookup"), ToolId("auth-email-lookup"))))

        `when`("one is abandoned, another remains") {
            then("advances, marking only that one declined") {
                strategy.transition(state, JourneyEvent.Abandoned(AuthSmsLookupDescriptor), ctx()) shouldBe
                    Transition.To(state.declining(ToolId("auth-sms-lookup")))
            }
        }
    }

    given("Credential, the last offered candidate is abandoned") {
        val state = LookupLoginState.Credential(Offer(listOf(ToolId("auth-sms-lookup"))))
        then("cancels - giving up on the very first proof is not an error") {
            strategy.transition(state, JourneyEvent.Abandoned(AuthSmsLookupDescriptor), ctx()) shouldBe Transition.Cancel
        }
    }

    given("Credential/AdditionalFactor, a proof just completed (settleOrRaise, after ActionCompleted)") {
        `when`("the floor is already satisfied") {
            val acc = account(method("sms", AcrLevel.LOA1))
            val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc), acrFloor = AcrLevel.LOA1)
            then("offers the optional device-binding prompt") {
                strategy.transition(LookupLoginState.Credential(Offer(listOf(ToolId("auth-sms-lookup")))), JourneyEvent.ActionCompleted, theCtx) shouldBe
                    Transition.To(LookupLoginState.OfferBinding)
            }
        }

        `when`("the floor is not yet satisfied, but another active method can help") {
            val acc = account(method("sms", AcrLevel.LOA2), method("password", AcrLevel.LOA2))
            val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc), acrFloor = AcrLevel.LOA2)
            then("offers it via AdditionalFactor") {
                strategy.transition(LookupLoginState.Credential(Offer(listOf(ToolId("auth-sms-lookup")))), JourneyEvent.ActionCompleted, theCtx) shouldBe
                    Transition.To(LookupLoginState.AdditionalFactor(Offer(listOf(ToolId("auth-password")))))
            }
        }

        `when`("nothing active can help, but re-identification could") {
            val acc = account(method("sms", AcrLevel.LOA2))
            val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc), acrFloor = AcrLevel.LOA2)
            then("requires the shared RE_IDENTIFY sub-journey - it only re-confirms this account, never adopts a different one") {
                strategy.transition(LookupLoginState.Credential(Offer(listOf(ToolId("auth-sms-lookup")))), JourneyEvent.ActionCompleted, theCtx) shouldBe
                    Transition.RequireSubJourney(
                        AuthIntent.RE_IDENTIFY,
                        seedWith = ReIdentifyState.forSubJourney(AcrLevel.LOA2, AcrLevel.LOA1),
                        resumeWith = LookupLoginState.Start
                    )
            }
        }

        `when`("nothing can help at all, not even re-identification (backend-disabled)") {
            val acc = account(method("sms", AcrLevel.LOA2))
            val theCtx = ctx(
                account = acc,
                evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc),
                acrFloor = AcrLevel.LOA2,
                availableTools = StrategyTestFixtures.allToolIds - setOf(ToolId("ident-fsc"), ToolId("ident-eid"), ToolId("ident-nect"))
            )
            then("aborts with a reason - never a silent enrollment fallback (this intent has none)") {
                val transition = strategy.transition(LookupLoginState.Credential(Offer(listOf(ToolId("auth-sms-lookup")))), JourneyEvent.ActionCompleted, theCtx)
                transition.shouldBeInstanceOf<Transition.Abort>()
                (transition as Transition.Abort).reason.template shouldContain "nicht erreichbar"
            }
        }
    }

    given("OfferBinding") {
        val state = LookupLoginState.OfferBinding

        then("accepting links the device, then finishes once resumed") {
            strategy.transition(state, JourneyEvent.Answered("accept"), ctx()) shouldBe
                Transition.Perform(Action.LinkDevice, resumeState = state)
            strategy.transition(state, JourneyEvent.ActionCompleted, ctx()) shouldBe Transition.Authenticated
        }

        then("declining finishes without linking") {
            strategy.transition(state, JourneyEvent.Answered("decline"), ctx()) shouldBe Transition.Authenticated
        }

        then("an unrecognized answer fails loudly") {
            shouldThrow<IllegalStateException> { strategy.transition(state, JourneyEvent.Answered("maybe"), ctx()) }
        }

        then("any non-Answered/ActionCompleted event is rejected - this state never runs a tool") {
            shouldThrow<IllegalStateException> { strategy.transition(state, JourneyEvent.Started, ctx()) }
        }
    }

    given("onCancel") {
        then("always falls back to ANONYMOUS - this intent never carries a durable account binding of its own") {
            strategy.cancelledTo(LookupLoginState.Start) shouldBe ChannelState.ANONYMOUS
            strategy.cancelledTo(LookupLoginState.Credential(Offer(listOf(ToolId("auth-sms-lookup"))))) shouldBe ChannelState.ANONYMOUS
        }
    }
})
