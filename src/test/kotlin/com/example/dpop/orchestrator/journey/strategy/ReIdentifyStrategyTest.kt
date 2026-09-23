package com.example.dpop.orchestrator.journey.strategy

import com.example.dpop.orchestrator.journey.state.Offer
import com.example.dpop.id_fsc.IdentFscDescriptor
import com.example.dpop.orchestrator.journey.Action
import com.example.dpop.orchestrator.kernel.AuthIntent
import com.example.dpop.orchestrator.journey.JourneyEvent
import com.example.dpop.orchestrator.journey.Transition
import com.example.dpop.orchestrator.journey.state.ReIdentifyState
import com.example.dpop.orchestrator.journey.strategy.StrategyTestFixtures.account
import com.example.dpop.orchestrator.journey.strategy.StrategyTestFixtures.ctx
import com.example.dpop.orchestrator.journey.strategy.StrategyTestFixtures.method
import com.example.dpop.orchestrator.policy.AuthEvidence
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
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Pure unit coverage of [ReIdentifyStrategy] - the one shared implementation `FAST_ACCESS`/
 * `LOOKUP_LOGIN`/`STEP_UP` all fall into once no active method reaches their target
 * (docs/04-orchestrierung.md, "RE_IDENTIFY"). No Spring context, no HTTP - the real tool catalog
 * (StrategyTestFixtures) plus hand-built states/events.
 */
class ReIdentifyStrategyTest : BehaviorSpec({

    val strategy = ReIdentifyStrategy()

    given("the intent") {
        then("is RE_IDENTIFY") {
            strategy.intent shouldBe AuthIntent.RE_IDENTIFY
        }
    }

    given("ReIdentifyState.forSubJourney") {
        then("seeds OfferReIdent with exactly the given target/starting acr") {
            ReIdentifyState.forSubJourney(AcrLevel.LOA2, AcrLevel.LOA1) shouldBe ReIdentifyState.OfferReIdent(AcrLevel.LOA2, AcrLevel.LOA1)
        }
    }

    given("Identifying, a completed tool") {
        val state = ReIdentifyState.Identifying(AcrLevel.LOA2, AcrLevel.LOA1, Offer(listOf(ToolId("ident-fsc"))))

        then("Identified always confirms the caller's already-known account, never adopts a different one") {
            val outcome = ToolOutcome.Completed.Identified(claims = listOf(com.example.dpop.tool_spi.Claim(com.example.dpop.tool_spi.AttributeType.PERSON_ID, "1", com.example.dpop.tool_spi.ClaimSource.EXT_STAMMDATEN)))
            val event = JourneyEvent.Completed(IdentFscDescriptor, outcome)
            strategy.transition(state, event, ctx()) shouldBe
                Transition.Perform(Action.RecordIdentification(IdentFscDescriptor, outcome), resumeState = state)
        }

        then("Authenticated is not offered by this intent") {
            shouldThrow<IllegalStateException> {
                strategy.transition(state, JourneyEvent.Completed(IdentFscDescriptor, ToolOutcome.Completed.Authenticated(amr = listOf("fsc"))), ctx())
            }
        }

        then("Enrolled is not offered by this intent") {
            shouldThrow<IllegalStateException> {
                strategy.transition(state, JourneyEvent.Completed(IdentFscDescriptor, ToolOutcome.Completed.Enrolled(enrollmentRef = EnrollmentRef("fsc", "ref"))), ctx())
            }
        }
    }

    given("OfferReIdent, with an IDENT tool that could still reach the target") {
        // sms already used, the IDENT tools not - only fsc is isolated here by marking eid and
        // nect used too, so the offered set is unambiguous.
        val acc = account(method("sms", AcrLevel.LOA2))
        val theCtx = ctx(
            account = acc,
            evidence = AuthEvidence.from(
                listOf("sms", "eid", "nect-epass"), setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE),
                amrSourceId = mapOf("nect-epass" to "ident-nect")
            ),
            acrFloor = AcrLevel.LOA2
        )
        val state = ReIdentifyState.OfferReIdent(AcrLevel.LOA2, AcrLevel.LOA1)

        `when`("accepted") {
            then("advances to Identifying, offering exactly the reachable IDENT tool(s)") {
                val transition = strategy.transition(state, JourneyEvent.Answered("accept"), theCtx)
                transition.shouldBeInstanceOf<Transition.To>()
                val to = (transition as Transition.To).state
                to.shouldBeInstanceOf<ReIdentifyState.Identifying>()
                to as ReIdentifyState.Identifying
                to.targetAcr shouldBe AcrLevel.LOA2
                to.startingAcr shouldBe AcrLevel.LOA1
                to.offered shouldContainExactly listOf(ToolId("ident-fsc"))
            }
        }

        `when`("declined") {
            then("cancels") {
                strategy.transition(state, JourneyEvent.Answered("decline"), theCtx) shouldBe Transition.Cancel
            }
        }

        `when`("an unrecognized answer is given") {
            then("fails loudly rather than guessing") {
                shouldThrow<IllegalStateException> { strategy.transition(state, JourneyEvent.Answered("maybe"), theCtx) }
            }
        }

        `when`("(re-)started without an answer yet") {
            then("re-presents the same prompt, unconditionally") {
                strategy.transition(state, JourneyEvent.Started, theCtx) shouldBe Transition.To(state)
            }
        }
    }

    given("OfferReIdent, no IDENT tool can close the gap (fsc, eid and nect already used this session)") {
        val acc = account(method("sms", AcrLevel.LOA2))
        val theCtx = ctx(
            account = acc,
            // Nect's amr is the procedure (`nect-eid`), not its method name - it counts as used
            // because ident-nect produced it.
            evidence = AuthEvidence.from(
                listOf("sms", "fsc", "eid", "nect-eid"), setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE),
                amrSourceId = mapOf("nect-eid" to "ident-nect")
            ),
            acrFloor = AcrLevel.LOA2
        )
        val state = ReIdentifyState.OfferReIdent(AcrLevel.LOA2, AcrLevel.LOA1)

        then("accepting still cancels rather than erroring") {
            strategy.transition(state, JourneyEvent.Answered("accept"), theCtx) shouldBe Transition.Cancel
        }
    }

    given("Identifying, more than one candidate offered") {
        val acc = account(method("sms", AcrLevel.LOA2))
        val theCtx = ctx(account = acc)
        val state = ReIdentifyState.Identifying(AcrLevel.LOA2, AcrLevel.LOA1, Offer(listOf(ToolId("ident-fsc"), ToolId("ident-eid"))))

        `when`("one is abandoned but another remains") {
            then("advances, marking only that one declined") {
                strategy.transition(state, JourneyEvent.Abandoned(IdentFscDescriptor), theCtx) shouldBe
                    Transition.To(state.declining(ToolId("ident-fsc")))
            }
        }

        `when`("the last remaining candidate is abandoned too") {
            val exhausted = state.withOffer(state.offer.copy(declined = setOf(ToolId("ident-eid"))))
            then("cancels - giving up here is not an error") {
                strategy.transition(exhausted, JourneyEvent.Abandoned(IdentFscDescriptor), theCtx) shouldBe Transition.Cancel
            }
        }

        `when`("a proof completes, then is resumed (ActionCompleted)") {
            then("finishes directly - the identification's own maxAcr already IS the achieved level") {
                val outcome = ToolOutcome.Completed.Identified(claims = listOf(com.example.dpop.tool_spi.Claim(com.example.dpop.tool_spi.AttributeType.PERSON_ID, "1", com.example.dpop.tool_spi.ClaimSource.EXT_STAMMDATEN)))
                val completed = JourneyEvent.Completed(IdentFscDescriptor, outcome)
                strategy.transition(state, completed, theCtx) shouldBe
                    Transition.Perform(Action.RecordIdentification(IdentFscDescriptor, outcome), resumeState = state)
                strategy.transition(state, JourneyEvent.ActionCompleted, theCtx) shouldBe Transition.Authenticated
            }
        }
    }

    given("onCancel") {
        then("falls back to ANONYMOUS when the caller had no session yet (FAST_ACCESS/LOOKUP_LOGIN)") {
            strategy.cancelledTo(ReIdentifyState.OfferReIdent(AcrLevel.LOA2, startingAcr = AcrLevel.NONE)) shouldBe ChannelState.ANONYMOUS
        }

        then("falls back to AUTHENTICATED when the caller was already authenticated (STEP_UP) - must not de-authenticate that session") {
            strategy.cancelledTo(ReIdentifyState.OfferReIdent(AcrLevel.LOA3, startingAcr = AcrLevel.LOA2)) shouldBe ChannelState.AUTHENTICATED
        }
    }
})
