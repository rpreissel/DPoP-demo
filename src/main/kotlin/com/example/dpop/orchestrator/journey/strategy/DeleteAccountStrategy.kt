package com.example.dpop.orchestrator.journey.strategy

import com.example.dpop.orchestrator.journey.Action
import com.example.dpop.orchestrator.journey.declineTool
import com.example.dpop.orchestrator.journey.AuthIntent
import com.example.dpop.orchestrator.journey.CandidateTools
import com.example.dpop.orchestrator.journey.IntentStrategy
import com.example.dpop.orchestrator.journey.JourneyContext
import com.example.dpop.orchestrator.journey.JourneyEvent
import com.example.dpop.orchestrator.journey.Transition
import com.example.dpop.orchestrator.journey.state.Offer
import com.example.dpop.orchestrator.journey.state.DeleteAccountState
import com.example.dpop.orchestrator.journey.state.StepUpState
import com.example.dpop.orchestrator.session.ChannelState
import com.example.dpop.tool_spi.AcrLevel
import com.example.dpop.tool_spi.ToolOutcome
import org.springframework.stereotype.Component

/**
 * Delete the account of an already authenticated channel (docs/05-api.md, Account löschen).
 *
 * The yes/no confirmation always comes FIRST, unconditionally, before any ACR check - asking
 * "do you really want to delete your account?" costs nothing and should never be gated behind a
 * step-up the caller may not even want to go through. Only once they actually accept does the
 * same GATE `ManageAuthMethodsStrategy` uses apply, at whatever level [Action.DeleteAccount.requiredAcr]
 * names for THIS account - loa2 for an identified one, only loa1 for one that never was (the
 * "Enrollment zuerst" case, see that fn's own doc): a hijacked session must not be able to delete
 * the account any more easily than it may add/remove a credential. If that gate needs a step-up,
 * the step-up itself already IS the fresh proof that would otherwise be asked for again right
 * afterwards, so deletion follows immediately; only when the channel already satisfies the
 * required level on its own (evidence of unknown age) does an explicit re-proof of any one active
 * factor run first - unlike an ordinary step-up, this re-proof is never skipped just because the
 * level was already reached, and accepts any active factor regardless of its own level (see
 * [DeleteAccountState]).
 *
 * That re-proof's own outcome is deliberately never recorded as `MethodEvidence` (a known
 * behaviour change from the pre-`transition()` design, docs/ideen/journey-strategie-
 * vereinheitlichung.md #5): unlike every other proof in this journey, it authorizes exactly one
 * immediate action, never a durable claim about what this account can prove again later - so it
 * goes straight to [Action.DeleteAccount] instead of first through [Action.AcceptProof].
 */
@Component
class DeleteAccountStrategy : IntentStrategy<DeleteAccountState> {

    override val intent = AuthIntent.DELETE_ACCOUNT

    override fun initialState(ctx: JourneyContext): DeleteAccountState = DeleteAccountState.ConfirmPending

    override fun transition(state: DeleteAccountState, event: JourneyEvent, ctx: JourneyContext): Transition =
        when (state) {
            is DeleteAccountState.ConfirmPending -> when (event) {
                is JourneyEvent.Answered -> when (event.answer) {
                    // No gate before this point (see class doc) - it only applies once the user
                    // actually said yes.
                    "accept" -> gate(ctx) ?: offerReconfirmation(ctx)
                    "decline" -> Transition.Cancel
                    else -> error("ConfirmPending does not understand answer '${event.answer}'")
                }
                // Resumed after gate()'s own step-up - only reachable via a prior "accept" (gate()
                // is the sole RequireSubJourney site here, and only from the "accept" branch), but
                // checked explicitly rather than assumed: a future second sub-journey type, or one
                // that stopped short of loa2, must not be silently mistaken for sufficient proof.
                //
                // Falling short (or a foreign sub-journey entirely) must NOT fall back to
                // offerReconfirmation() - that fallback accepts ANY active factor "at any level"
                // (CandidateTools.forReconfirmation), which exists only for the OTHER case in this
                // class's doc (the session already independently satisfies loa2, one more fresh
                // proof is just an anti-CSRF check). Reusing it here would let a session that can
                // only ever prove loa1 factors - exactly why the step-up needed RE_IDENTIFY in the
                // first place - delete the account anyway by just re-proving that same loa1 factor,
                // defeating the loa2 gate this class's own doc says must hold.
                is JourneyEvent.SubJourneyFinished -> {
                    val account = ctx.requireAccount()
                    if (event.intent == AuthIntent.STEP_UP && AcrLevel.rank(event.achievedAcr) >= AcrLevel.rank(Action.DeleteAccount.requiredAcr(account))) {
                        Transition.Perform(Action.DeleteAccount, resumeState = state)
                    } else {
                        Transition.Cancel
                    }
                }
                // The gate's own STEP_UP was declined instead - same reasoning as above, not a
                // lesser fallback.
                is JourneyEvent.SubJourneyCancelled -> Transition.Cancel
                // The gate's own step-up proof just finished executing the delete above - end the channel.
                is JourneyEvent.ActionCompleted -> Transition.Logout
                // Started: always present the prompt, unconditionally.
                else -> Transition.To(state)
            }

            is DeleteAccountState.ConfirmationRequired -> when (event) {
                is JourneyEvent.Abandoned -> {
                    declineTool(state, event.tool.toolId, ctx) { Transition.Cancel }
                }
                // Any active factor, at any level, is sufficient (CandidateTools.forReconfirmation)
                // - there is no further step once one was proven, straight to the delete (see
                // class doc for why this skips Action.AcceptProof).
                is JourneyEvent.Completed -> when (event.outcome) {
                    is ToolOutcome.Completed.Authenticated ->
                        Transition.Perform(Action.DeleteAccount, resumeState = state)
                    is ToolOutcome.Completed.Identified, is ToolOutcome.Completed.Enrolled, is ToolOutcome.Completed.Approved, is ToolOutcome.Completed.Attested ->
                        error("${event.tool.toolId} is not offered by DELETE_ACCOUNT")
                }
                // The delete just ran - end the channel.
                is JourneyEvent.ActionCompleted -> Transition.Logout
                else -> error("ConfirmationRequired does not understand $event")
            }
        }

    override fun cancelledTo(state: DeleteAccountState): ChannelState = ChannelState.AUTHENTICATED

    /** Null once the session already carries loa2 and the caller may proceed. */
    private fun gate(ctx: JourneyContext): Transition? {
        val account = ctx.requireAccount()
        val requiredAcr = Action.DeleteAccount.requiredAcr(account)
        if (ctx.policy.isSatisfied(ctx.evidence, requiredAcr, account)) return null
        return Transition.RequireSubJourney(
            AuthIntent.STEP_UP,
            seedWith = StepUpState.forSubJourney(requiredAcr, ctx.currentAcr),
            resumeWith = DeleteAccountState.ConfirmPending
        )
    }

    private fun offerReconfirmation(ctx: JourneyContext): Transition {
        val candidates = CandidateTools.forReconfirmation(ctx.requireAccount(), ctx)
        // No active factor left to re-prove is unreachable in practice (an AUTHENTICATED channel
        // implies at least one), but Abort - never a silent auto-delete - is the correct fallback
        // if it ever happened.
        return if (candidates.isEmpty()) {
            Transition.Abort("Kein aktiver Faktor zur erneuten Bestaetigung verfuegbar")
        } else {
            Transition.To(DeleteAccountState.ConfirmationRequired(Offer(candidates)))
        }
    }
}
