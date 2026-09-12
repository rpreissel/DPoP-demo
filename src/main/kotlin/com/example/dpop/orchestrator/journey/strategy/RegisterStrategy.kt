package com.example.dpop.orchestrator.journey.strategy

import com.example.dpop.orchestrator.journey.Action
import com.example.dpop.orchestrator.journey.AuthIntent
import com.example.dpop.orchestrator.journey.CandidateTools
import com.example.dpop.orchestrator.journey.IntentStrategy
import com.example.dpop.orchestrator.journey.JourneyContext
import com.example.dpop.orchestrator.journey.JourneyEvent
import com.example.dpop.orchestrator.journey.Transition
import com.example.dpop.orchestrator.journey.state.AuthChoice
import com.example.dpop.orchestrator.journey.state.Enrolling
import com.example.dpop.orchestrator.journey.state.OfferingState
import com.example.dpop.orchestrator.journey.state.RegisterState
import com.example.dpop.orchestrator.session.ChannelSession
import com.example.dpop.orchestrator.session.ChannelState
import com.example.dpop.tool_spi.MethodRole
import org.springframework.stereotype.Component

/**
 * REGISTER's own journey. See [RegisterState]'s own doc for the shape of it and why [AuthChoice]/
 * [Enrolling] are shared with [FastAccessState][com.example.dpop.orchestrator.journey.state.
 * FastAccessState] rather than owned exclusively here.
 */
@Component
class RegisterStrategy : IntentStrategy<RegisterState> {

    override val intent: AuthIntent = AuthIntent.REGISTER

    override fun initialState(ctx: JourneyContext): RegisterState = RegisterState.Start

    override fun transition(state: RegisterState, event: JourneyEvent, ctx: JourneyContext): Transition =
        when (state) {
            is RegisterState.Start -> when (event) {
                // A RE_IDENTIFY sub-journey started from this run's own offerEnrollment finished -
                // re-check satisfaction the same way FastAccessStrategy's own Start does.
                is JourneyEvent.SubJourneyFinished -> AuthEnrollCore.afterProof(ctx, resumeAtStart = RegisterState.Start)
                is JourneyEvent.SubJourneyCancelled -> Transition.Cancel
                else -> offerIdentification(ctx)
            }

            is RegisterState.Identifying -> when (event) {
                is JourneyEvent.Abandoned -> giveUpOrReoffer(state, event)
                is JourneyEvent.Completed -> Transition.Perform(AuthEnrollCore.proofAction(event), resumeState = state)
                // Deliberately never checks isSatisfied: identification evidence alone (amr=fsc)
                // trivially clears most floors, which would let a run finish without a single
                // durable credential ever being proven or created.
                else -> afterIdentification(ctx)
            }

            is AuthChoice -> when (event) {
                is JourneyEvent.Abandoned -> {
                    val declined = state.declined + event.tool.toolId
                    val remaining = state.copy(declined = declined, active = null)
                    if (remaining.exhausted(ctx.availableTools)) afterAuthDeclined(ctx, declined) else Transition.To(remaining)
                }
                is JourneyEvent.Completed -> Transition.Perform(AuthEnrollCore.proofAction(event), resumeState = state)
                // Deliberately never wrapped with the password obligation below: a rediscovered,
                // already-set-up account finishing here is treated as an ordinary login, not a
                // fresh registration (confirmed 2026-09-09) - same as the email obligation, which
                // explicitly never applies on this path either.
                else -> AuthEnrollCore.afterProof(ctx, resumeAtStart = RegisterState.Start)
            }

            is RegisterState.ConfirmDeviceRebind -> when (event) {
                is JourneyEvent.Answered -> when (event.answer) {
                    ACCEPT -> Transition.Perform(Action.LinkDevice(state.accountId), resumeState = state)
                    DECLINE -> Transition.Cancel
                    else -> error("ConfirmDeviceRebind does not understand answer '${event.answer}'")
                }
                is JourneyEvent.ActionCompleted -> afterIdentification(ctx)
                else -> error("ConfirmDeviceRebind only accepts JourneyEvent.Answered")
            }

            is RegisterState.ConfirmingEmail -> when (event) {
                is JourneyEvent.Abandoned -> AuthEnrollCore.reoffer(state)
                is JourneyEvent.Completed -> Transition.Perform(AuthEnrollCore.proofAction(event), resumeState = state)
                else -> afterEnrollment(ctx, emailObligation = false)
            }

            is Enrolling -> when (event) {
                is JourneyEvent.Abandoned -> AuthEnrollCore.reoffer(state)
                is JourneyEvent.Completed -> Transition.Perform(AuthEnrollCore.proofAction(event), resumeState = state)
                else -> afterEnrollment(ctx, state.emailObligation)
            }

            is RegisterState.PasswordObligation -> when (event) {
                is JourneyEvent.Abandoned -> AuthEnrollCore.reoffer(state)
                is JourneyEvent.Completed -> Transition.Perform(AuthEnrollCore.proofAction(event), resumeState = state)
                // Reached only after the email obligation (if any) already discharged - see this
                // state's own KDoc - so the re-check below never has one still open.
                else -> afterEnrollment(ctx, emailObligation = false)
            }
        }

    override fun cancelledTo(state: RegisterState): ChannelState = ChannelState.ANONYMOUS

    // Offers -------------------------------------------------------------------

    private fun offerIdentification(ctx: JourneyContext): Transition {
        val idents = CandidateTools.forIdentification(ctx)
        return if (idents.isEmpty()) {
            Transition.Abort("Kein Identifizierungsverfahren verfuegbar")
        } else {
            Transition.To(RegisterState.Identifying(idents))
        }
    }

    /** Nothing (or nothing else) provable is left: re-identifying is the only way forward from here. */
    private fun afterAuthDeclined(ctx: JourneyContext, alreadyDeclined: Set<String>): Transition {
        val account = ctx.account
        if (account != null) {
            val remaining = CandidateTools.forAuth(account, ctx.acrFloor, ctx) - alreadyDeclined
            if (remaining.isNotEmpty()) {
                return Transition.To(AuthChoice(remaining, declined = emptySet()))
            }
        }
        return offerIdentification(ctx)
    }

    private fun afterIdentification(ctx: JourneyContext): Transition {
        val account = ctx.requireAccount()
        // This device is already durably linked to a DIFFERENT account (the "Zweitaccount" case,
        // docs/04-orchestrierung.md #2) - ask before silently taking it over, as early as possible
        // and before any method is offered. Checked first, unconditionally: every later step in
        // this journey runs only after this has already been resolved once.
        if (ctx.linkedAccountId != null && ctx.linkedAccountId != account.accountId) {
            return Transition.To(RegisterState.ConfirmDeviceRebind(account.accountId))
        }
        // An account found again by KVNR may already have everything it needs - offering an
        // existing method to prove beats an enrollment list that would come back empty.
        if (ctx.policy.canAccountReach(account, ctx.acrFloor)) {
            val candidates = CandidateTools.forAuth(account, ctx.acrFloor, ctx)
            if (candidates.isNotEmpty()) return Transition.To(AuthChoice(candidates))
        }
        return AuthEnrollCore.offerEnrollment(account, ctx, emailObligation = true, resumeAtStart = RegisterState.Start)
    }

    /**
     * Wraps [AuthEnrollCore.afterEnrollment] rather than re-deriving reachability/sufficiency
     * itself: only ever intercepts the one outcome that means "this run would finish right now"
     * ([Transition.Authenticated]) and redirects it - every other outcome (still short of the
     * floor, the email obligation still open) is none of this method's business and passes through
     * unchanged. This is also why the order falls out correctly without this needing to know about
     * it: the shared code already only returns [Transition.Authenticated] once any email obligation
     * is discharged, so password is necessarily checked last.
     *
     * Web-only (docs/04-orchestrierung.md #8, [RegisterState.PasswordObligation]'s own KDoc): only
     * the KEYCLOAK channel must always end up with a password credential in addition to the shared
     * email obligation - `enroll-sms` stays a free choice on both channels, only `password` is
     * unconditionally required, and only on the Web.
     */
    private fun afterEnrollment(ctx: JourneyContext, emailObligation: Boolean): Transition {
        val base = AuthEnrollCore.afterEnrollment(ctx, emailObligation, resumeAtStart = RegisterState.Start)
        if (base != Transition.Authenticated || ctx.channel != ChannelSession.Channel.KEYCLOAK) return base

        val account = ctx.requireAccount()
        if (account.activeAuthenticationMethods.any { it.method == PASSWORD_METHOD }) return base
        val candidates = passwordEnrollmentCandidates(ctx)
        return if (candidates.isNotEmpty()) Transition.To(RegisterState.PasswordObligation(candidates)) else base
    }

    /**
     * The `password` METHOD is the one intentionally hardcoded name in this strategy - never a
     * toolId, unlike a naive `"enroll-password"` literal would be: which concrete tool provides it
     * is a catalog/availability question like every other candidate list ([CandidateTools]'s own
     * pattern), because App and Web could register different tools for the same method. Empty when
     * no such tool is currently offerable (unavailable, kill-switched) - the caller falls through
     * to the shared behaviour rather than dead-ending on an obligation nothing can fulfil.
     */
    private fun passwordEnrollmentCandidates(ctx: JourneyContext): List<String> =
        ctx.catalog.descriptors()
            .filter { it.role == MethodRole.ENROLLMENT && it.method == PASSWORD_METHOD }
            .map { it.toolId }
            .filter { it in ctx.availableTools }

    /** Abandoning the last fallback state is giving up on the journey, not an error. */
    private fun giveUpOrReoffer(state: OfferingState, event: JourneyEvent.Abandoned): Transition {
        val declined = state.declined + event.tool.toolId
        val remaining = state.offered.toSet() - declined
        return if (remaining.isEmpty()) {
            Transition.Cancel
        } else {
            Transition.To(RegisterState.Identifying(state.offered, declined))
        }
    }

    private companion object {
        const val PASSWORD_METHOD = "password"

        /** The two answers [RegisterState.ConfirmDeviceRebind] understands (see JourneyEvent.Answered). */
        const val ACCEPT = "accept"
        const val DECLINE = "decline"
    }
}
