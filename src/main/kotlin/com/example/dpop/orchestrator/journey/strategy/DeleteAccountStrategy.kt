package com.example.dpop.orchestrator.journey.strategy

import com.example.dpop.orchestrator.journey.AuthIntent
import com.example.dpop.orchestrator.journey.CandidateTools
import com.example.dpop.orchestrator.journey.Decision
import com.example.dpop.orchestrator.journey.DELETE_ACCOUNT_REQUIRED_ACR
import com.example.dpop.orchestrator.journey.IntentStrategy
import com.example.dpop.orchestrator.journey.Interpretation
import com.example.dpop.orchestrator.journey.JourneyContext
import com.example.dpop.orchestrator.journey.JourneyEvent
import com.example.dpop.orchestrator.journey.state.DeleteAccountState
import com.example.dpop.orchestrator.session.AcrLevels
import com.example.dpop.orchestrator.session.ChannelState
import com.example.dpop.tool_spi.ToolDescriptor
import com.example.dpop.tool_spi.ToolOutcome
import org.springframework.stereotype.Component

/**
 * Delete the account of an already authenticated channel (docs/05-api.md, Account löschen).
 *
 * The yes/no confirmation always comes FIRST, unconditionally, before any loa2 check - asking
 * "do you really want to delete your account?" costs nothing and should never be gated behind a
 * step-up the caller may not even want to go through. Only once they actually accept does the
 * same loa2 GATE `ManageAuthMethodsStrategy` uses apply: a hijacked loa1 session must not be able
 * to delete the account any more than it may add/remove a credential. If that gate needs a
 * step-up, the step-up itself already IS the fresh proof that would otherwise be asked for again
 * right afterwards, so deletion follows immediately; only when the channel already satisfied loa2
 * on its own (evidence of unknown age) does an explicit re-proof of any one active factor run
 * first - unlike an ordinary step-up, this re-proof is never skipped just because loa2 was already
 * reached, and accepts any active factor regardless of its own level (see [DeleteAccountState]).
 */
@Component
class DeleteAccountStrategy : IntentStrategy<DeleteAccountState> {

    override val intent = AuthIntent.DELETE_ACCOUNT

    override fun initialState(ctx: JourneyContext): DeleteAccountState = DeleteAccountState.ConfirmPending

    override fun interpret(state: DeleteAccountState, tool: ToolDescriptor, outcome: ToolOutcome.Completed): Interpretation =
        when (outcome) {
            // The account is already known from the channel; the point of this proof is only to
            // show the caller is still present, not to (re-)establish which account it is.
            is ToolOutcome.Completed.Authenticated ->
                Interpretation.AcceptProof(useOutcomeAccount = false, bindDevice = false)
            is ToolOutcome.Completed.Identified,
            is ToolOutcome.Completed.Enrolled ->
                error("${tool.toolId} is not offered by DELETE_ACCOUNT")
        }

    override fun next(state: DeleteAccountState, event: JourneyEvent, ctx: JourneyContext): Decision =
        when (state) {
            is DeleteAccountState.ConfirmPending -> when (event) {
                is JourneyEvent.Answered -> when (event.answer) {
                    // No gate before this point (see class doc) - it only applies once the user
                    // actually said yes.
                    "accept" -> gate(ctx) ?: offerReconfirmation(ctx)
                    "decline" -> Decision.Cancel
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
                is JourneyEvent.SubJourneyFinished ->
                    if (event.intent == AuthIntent.STEP_UP && AcrLevels.rank(event.achievedAcr) >= AcrLevels.rank(DELETE_ACCOUNT_REQUIRED_ACR)) {
                        Decision.DeleteAccount(ctx.requireAccount().accountId)
                    } else {
                        Decision.Cancel
                    }
                // The gate's own STEP_UP was declined instead - same reasoning as above, not a
                // lesser fallback.
                is JourneyEvent.SubJourneyCancelled -> Decision.Cancel
                // Started: always present the prompt, unconditionally.
                else -> Decision.Advance(state)
            }

            is DeleteAccountState.ConfirmationRequired -> when (event) {
                is JourneyEvent.Abandoned -> {
                    val declined = state.declined + event.tool.toolId
                    if ((state.offered.toSet() - declined).isEmpty()) Decision.Cancel
                    else Decision.Advance(state.copy(declined = declined, active = null))
                }
                // Any active factor, at any level, is sufficient (docs/orchestrator/journey/CandidateTools.kt,
                // forReconfirmation) - there is no further step once one was proven.
                else -> Decision.DeleteAccount(ctx.requireAccount().accountId)
            }
        }

    override fun onCancel(state: DeleteAccountState): ChannelState = ChannelState.AUTHENTICATED

    /** Null once the session already carries loa2 and the caller may proceed. */
    private fun gate(ctx: JourneyContext): Decision? {
        val account = ctx.requireAccount()
        if (ctx.policy.isSatisfied(ctx.evidence, DELETE_ACCOUNT_REQUIRED_ACR, account)) return null
        return Decision.RequireSubJourney(AuthIntent.STEP_UP, DELETE_ACCOUNT_REQUIRED_ACR, resumeWith = DeleteAccountState.ConfirmPending)
    }

    private fun offerReconfirmation(ctx: JourneyContext): Decision {
        val candidates = CandidateTools.forReconfirmation(ctx.requireAccount(), ctx)
        // No active factor left to re-prove is unreachable in practice (an AUTHENTICATED channel
        // implies at least one), but Abort - never a silent auto-delete - is the correct fallback
        // if it ever happened.
        return if (candidates.isEmpty()) {
            Decision.Abort("Kein aktiver Faktor zur erneuten Bestaetigung verfuegbar")
        } else {
            Decision.Advance(DeleteAccountState.ConfirmationRequired(candidates))
        }
    }
}
