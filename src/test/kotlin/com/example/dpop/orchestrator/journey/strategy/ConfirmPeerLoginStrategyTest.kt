package com.example.dpop.orchestrator.journey.strategy

import com.example.dpop.auth_sms.AuthSmsUseDescriptor
import com.example.dpop.orchestrator.journey.Action
import com.example.dpop.orchestrator.journey.AuthIntent
import com.example.dpop.orchestrator.journey.JourneyEvent
import com.example.dpop.orchestrator.journey.Transition
import com.example.dpop.orchestrator.journey.state.ConfirmPeerLoginState
import com.example.dpop.orchestrator.journey.state.StepUpState
import com.example.dpop.orchestrator.journey.strategy.StrategyTestFixtures.account
import com.example.dpop.orchestrator.journey.strategy.StrategyTestFixtures.ctx
import com.example.dpop.orchestrator.journey.strategy.StrategyTestFixtures.evidence
import com.example.dpop.orchestrator.journey.strategy.StrategyTestFixtures.method
import com.example.dpop.orchestrator.session.ChannelState
import com.example.dpop.tool_spi.FactorType
import com.example.dpop.tool_spi.ToolOutcome
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Pure unit coverage of [ConfirmPeerLoginStrategy] - approving/declining a WEB-channel QR pairing
 * (docs/04-orchestrierung.md, CONFIRM_PEER_LOGIN). No Spring context, no HTTP. Mirrors
 * [DeleteAccountStrategyTest] deliberately: both intents share the exact same
 * "loa2 evidence of unknown age still needs one fresh re-proof, a just-finished gate step-up does
 * not" shape (see [ConfirmPeerLoginStrategy]'s own class doc).
 */
class ConfirmPeerLoginStrategyTest : BehaviorSpec({

    val strategy = ConfirmPeerLoginStrategy()

    given("the intent") {
        then("is CONFIRM_PEER_LOGIN") {
            strategy.intent shouldBe AuthIntent.CONFIRM_PEER_LOGIN
        }
    }

    given("initialState") {
        then("is Requested(startedAuthenticated = false) - the cold-entry default") {
            strategy.initialState(ctx()) shouldBe ConfirmPeerLoginState.Requested(startedAuthenticated = false)
        }
    }

    given("Requested, a cold entry with no account at all") {
        then("aborts right here - never falls into identification/registration") {
            val transition = strategy.transition(ConfirmPeerLoginState.Requested(false), JourneyEvent.Started, ctx(account = null))
            transition.shouldBeInstanceOf<Transition.Abort>()
        }
    }

    given("Requested, just started") {
        `when`("the session does not yet carry loa2") {
            val acc = account(method("sms", "loa1"))
            val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc))
            then("the loa2 gate parks the wish and demands a step-up first") {
                strategy.transition(ConfirmPeerLoginState.Requested(false), JourneyEvent.Started, theCtx) shouldBe
                    Transition.RequireSubJourney(
                        AuthIntent.STEP_UP,
                        seedWith = StepUpState.forSubJourney("loa2", "loa1", allowReIdentification = false),
                        resumeWith = ConfirmPeerLoginState.Requested(false)
                    )
            }
        }

        `when`("the session already carries loa2") {
            // device is the only tool whose own maxAcr reaches loa2 alone (sms/password/email cap
            // at loa1) - so this is the only single-method way to seed "already at loa2" evidence.
            val acc = account(method("device", "loa2", details = StrategyTestFixtures.deviceDetails()))
            val theCtx = ctx(account = acc, evidence = evidence(listOf("device"), setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE, FactorType.INHERENCE), account = acc), acrFloor = "loa1")
            then("still demands one fresh re-proof of any active factor - evidence of unknown age is never enough on its own to vouch for a foreign login") {
                val transition = strategy.transition(ConfirmPeerLoginState.Requested(true), JourneyEvent.Started, theCtx)
                transition.shouldBeInstanceOf<Transition.To>()
                val to = (transition as Transition.To).state
                to.shouldBeInstanceOf<ConfirmPeerLoginState.ConfirmationRequired>()
                (to as ConfirmPeerLoginState.ConfirmationRequired).offered shouldContainExactlyInAnyOrder listOf("auth-device")
                to.startedAuthenticated shouldBe true
            }
        }
    }

    given("Requested, resumed after a sub-journey (SubJourneyFinished)") {
        `when`("it was the gate's own STEP_UP, and it reached loa2") {
            then("goes straight to Confirming - that fresh proof already IS the re-confirmation, no second one demanded") {
                val event = JourneyEvent.SubJourneyFinished(AuthIntent.STEP_UP, achievedAcr = "loa2")
                strategy.transition(ConfirmPeerLoginState.Requested(false), event, ctx()) shouldBe
                    Transition.To(ConfirmPeerLoginState.Confirming(false))
            }
        }

        `when`("it was a STEP_UP that fell short of loa2") {
            val acc = account(method("sms", "loa1"))
            val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc))
            then("re-evaluates from scratch instead of silently accepting it as sufficient") {
                val event = JourneyEvent.SubJourneyFinished(AuthIntent.STEP_UP, achievedAcr = "loa1")
                strategy.transition(ConfirmPeerLoginState.Requested(false), event, theCtx) shouldBe
                    Transition.RequireSubJourney(
                        AuthIntent.STEP_UP,
                        seedWith = StepUpState.forSubJourney("loa2", "loa1", allowReIdentification = false),
                        resumeWith = ConfirmPeerLoginState.Requested(false)
                    )
            }
        }

        `when`("it was a different sub-journey entirely - never assumed to be the gate's own") {
            then("re-evaluates from scratch") {
                val acc = account(method("sms", "loa1"))
                val theCtx = ctx(account = acc, evidence = evidence(listOf("sms"), setOf(FactorType.POSSESSION), account = acc))
                val event = JourneyEvent.SubJourneyFinished(AuthIntent.RE_IDENTIFY, achievedAcr = "loa3")
                strategy.transition(ConfirmPeerLoginState.Requested(false), event, theCtx) shouldBe
                    Transition.RequireSubJourney(
                        AuthIntent.STEP_UP,
                        seedWith = StepUpState.forSubJourney("loa2", "loa1", allowReIdentification = false),
                        resumeWith = ConfirmPeerLoginState.Requested(false)
                    )
            }
        }
    }

    given("Requested, the gate's own step-up was declined instead (SubJourneyCancelled)") {
        then("cancels the whole wish - not re-requested forever") {
            val event = JourneyEvent.SubJourneyCancelled(AuthIntent.STEP_UP)
            strategy.transition(ConfirmPeerLoginState.Requested(false), event, ctx()) shouldBe Transition.Cancel
        }
    }

    given("ConfirmationRequired, more than one offered candidate") {
        val state = ConfirmPeerLoginState.ConfirmationRequired(false, listOf("auth-sms", "auth-password"))
        then("abandoning one keeps the choice among the rest") {
            strategy.transition(state, JourneyEvent.Abandoned(AuthSmsUseDescriptor), ctx()) shouldBe
                Transition.To(state.copy(declined = setOf("auth-sms"), active = null))
        }
    }

    given("ConfirmationRequired, the last offered candidate is abandoned") {
        val state = ConfirmPeerLoginState.ConfirmationRequired(false, listOf("auth-sms"))
        then("cancels - the peer login is never confirmed just because every re-proof option was declined") {
            strategy.transition(state, JourneyEvent.Abandoned(AuthSmsUseDescriptor), ctx()) shouldBe Transition.Cancel
        }
    }

    given("ConfirmationRequired, any active factor is re-proven") {
        val state = ConfirmPeerLoginState.ConfirmationRequired(true, listOf("auth-sms"))
        then("moves on to Confirming - one proof, at any level, is always sufficient here") {
            val event = JourneyEvent.Completed(AuthSmsUseDescriptor, ToolOutcome.Completed.Authenticated(amr = listOf("sms")))
            strategy.transition(state, event, ctx()) shouldBe Transition.To(ConfirmPeerLoginState.Confirming(true))
        }
    }

    given("ConfirmationRequired, an outcome this state never offers") {
        val state = ConfirmPeerLoginState.ConfirmationRequired(false, listOf("ident-fsc"))
        then("fails loudly rather than silently confirming") {
            shouldThrow<IllegalStateException> {
                strategy.transition(
                    state,
                    JourneyEvent.Completed(com.example.dpop.id_fsc.IdentFscDescriptor, ToolOutcome.Completed.Identified(personId = 1L)),
                    ctx()
                )
            }
        }
    }

    given("Confirming, the approval just ran (Completed)") {
        then("performs RecordApproval") {
            val acc = account(method("sms", "loa1"))
            val theCtx = ctx(account = acc)
            val state = ConfirmPeerLoginState.Confirming(false)
            val outcome = ToolOutcome.Completed.Approved()
            val event = JourneyEvent.Completed(com.example.dpop.auth_qr.ConfirmQrLoginDescriptor, outcome)
            strategy.transition(state, event, theCtx) shouldBe Transition.Perform(Action.RecordApproval(com.example.dpop.auth_qr.ConfirmQrLoginDescriptor, outcome), resumeState = state)
        }
    }

    given("onCancel") {
        then("always falls back to AUTHENTICATED - the shared loa2 gate only ever runs on a known account") {
            strategy.cancelledTo(ConfirmPeerLoginState.Requested(false)) shouldBe ChannelState.AUTHENTICATED
            strategy.cancelledTo(ConfirmPeerLoginState.ConfirmationRequired(false, listOf("auth-sms"))) shouldBe ChannelState.AUTHENTICATED
        }
    }
})
