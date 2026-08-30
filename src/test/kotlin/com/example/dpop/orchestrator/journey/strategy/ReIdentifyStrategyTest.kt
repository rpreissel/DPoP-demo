package com.example.dpop.orchestrator.journey.strategy

import com.example.dpop.id_fsc.IdentFscDescriptor
import com.example.dpop.orchestrator.journey.AuthIntent
import com.example.dpop.orchestrator.journey.Decision
import com.example.dpop.orchestrator.journey.Interpretation
import com.example.dpop.orchestrator.journey.JourneyEvent
import com.example.dpop.orchestrator.journey.state.ReIdentifyState
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

    given("initialStateForSubJourneyAcr") {
        then("seeds OfferReIdent with exactly the given target/starting acr") {
            strategy.initialStateForSubJourneyAcr("loa2", "loa1") shouldBe ReIdentifyState.OfferReIdent("loa2", "loa1")
        }
    }

    given("interpret") {
        val state = ReIdentifyState.OfferReIdent("loa2", "loa1")

        then("Identified always confirms the caller's already-known account, never adopts a different one") {
            strategy.interpret(state, IdentFscDescriptor, ToolOutcome.Completed.Identified(personId = 1L)) shouldBe Interpretation.ConfirmIdentity
        }

        then("Authenticated is not offered by this intent") {
            shouldThrow<IllegalStateException> {
                strategy.interpret(state, IdentFscDescriptor, ToolOutcome.Completed.Authenticated(amr = listOf("fsc")))
            }
        }

        then("Enrolled is not offered by this intent") {
            shouldThrow<IllegalStateException> {
                strategy.interpret(state, IdentFscDescriptor, ToolOutcome.Completed.Enrolled(enrollmentRef = com.example.dpop.tool_spi.EnrollmentRef("fsc", "ref")))
            }
        }
    }

    given("OfferReIdent, with an IDENT tool that could still reach the target") {
        // sms already used, fsc/eid not - only fsc is isolated here by marking eid used too, so
        // the offered set is unambiguous.
        val acc = account(method("sms", "loa2"))
        val theCtx = ctx(account = acc, evidence = AuthEvidence(listOf("sms", "eid"), setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE)), acrFloor = "loa2")
        val state = ReIdentifyState.OfferReIdent("loa2", "loa1")

        `when`("accepted") {
            then("advances to Identifying, offering exactly the reachable IDENT tool(s)") {
                val decision = strategy.next(state, JourneyEvent.Answered("accept"), theCtx)
                decision.shouldBeInstanceOf<Decision.Advance>()
                val to = (decision as Decision.Advance).to
                to.shouldBeInstanceOf<ReIdentifyState.Identifying>()
                to as ReIdentifyState.Identifying
                to.targetAcr shouldBe "loa2"
                to.startingAcr shouldBe "loa1"
                to.offered shouldContainExactly listOf("ident-fsc")
            }
        }

        `when`("declined") {
            then("cancels") {
                strategy.next(state, JourneyEvent.Answered("decline"), theCtx) shouldBe Decision.Cancel
            }
        }

        `when`("an unrecognized answer is given") {
            then("fails loudly rather than guessing") {
                shouldThrow<IllegalStateException> { strategy.next(state, JourneyEvent.Answered("maybe"), theCtx) }
            }
        }

        `when`("(re-)started without an answer yet") {
            then("re-presents the same prompt, unconditionally") {
                strategy.next(state, JourneyEvent.Started, theCtx) shouldBe Decision.Advance(state)
            }
        }
    }

    given("OfferReIdent, no IDENT tool can close the gap (both fsc and eid already used this session)") {
        val acc = account(method("sms", "loa2"))
        val theCtx = ctx(
            account = acc,
            evidence = AuthEvidence(listOf("sms", "fsc", "eid"), setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE)),
            acrFloor = "loa2"
        )
        val state = ReIdentifyState.OfferReIdent("loa2", "loa1")

        then("accepting still cancels rather than erroring") {
            strategy.next(state, JourneyEvent.Answered("accept"), theCtx) shouldBe Decision.Cancel
        }
    }

    given("Identifying, more than one candidate offered") {
        val acc = account(method("sms", "loa2"))
        val theCtx = ctx(account = acc)
        val state = ReIdentifyState.Identifying("loa2", "loa1", listOf("ident-fsc", "ident-eid"))

        `when`("one is abandoned but another remains") {
            then("advances, marking only that one declined") {
                strategy.next(state, JourneyEvent.Abandoned(IdentFscDescriptor), theCtx) shouldBe
                    Decision.Advance(state.copy(declined = setOf("ident-fsc"), active = null))
            }
        }

        `when`("the last remaining candidate is abandoned too") {
            val exhausted = state.copy(declined = setOf("ident-eid"))
            then("cancels - giving up here is not an error") {
                strategy.next(exhausted, JourneyEvent.Abandoned(IdentFscDescriptor), theCtx) shouldBe Decision.Cancel
            }
        }

        `when`("a proof completes") {
            then("finishes directly - the identification's own maxAcr already IS the achieved level") {
                val completed = JourneyEvent.Completed(IdentFscDescriptor, ToolOutcome.Completed.Identified(personId = 1L))
                strategy.next(state, completed, theCtx) shouldBe Decision.Finish
            }
        }
    }

    given("onCancel") {
        then("falls back to ANONYMOUS when the caller had no session yet (FAST_ACCESS/LOOKUP_LOGIN)") {
            strategy.onCancel(ReIdentifyState.OfferReIdent("loa2", startingAcr = "none")) shouldBe ChannelState.ANONYMOUS
        }

        then("falls back to AUTHENTICATED when the caller was already authenticated (STEP_UP) - must not de-authenticate that session") {
            strategy.onCancel(ReIdentifyState.OfferReIdent("loa3", startingAcr = "loa2")) shouldBe ChannelState.AUTHENTICATED
        }
    }
})
