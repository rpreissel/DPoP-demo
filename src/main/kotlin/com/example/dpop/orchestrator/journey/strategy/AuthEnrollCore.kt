package com.example.dpop.orchestrator.journey.strategy

import com.example.dpop.texts.Text
import com.example.dpop.account.AccountProfile
import com.example.dpop.orchestrator.domain.journey.Action
import com.example.dpop.orchestrator.domain.AuthIntent
import com.example.dpop.orchestrator.domain.journey.CandidateTools
import com.example.dpop.orchestrator.domain.journey.JourneyContext
import com.example.dpop.orchestrator.domain.journey.JourneyEvent
import com.example.dpop.orchestrator.domain.journey.Transition
import com.example.dpop.orchestrator.domain.journey.toEnrollAbortMessage
import com.example.dpop.orchestrator.domain.journey.state.Offer
import com.example.dpop.orchestrator.domain.journey.state.AuthChoice
import com.example.dpop.orchestrator.domain.journey.state.Enrolling
import com.example.dpop.orchestrator.domain.journey.state.JourneyState
import com.example.dpop.orchestrator.domain.journey.state.ReIdentifyState
import com.example.dpop.orchestrator.domain.journey.state.RegisterState
import com.example.dpop.orchestrator.domain.policy.Reachability
import com.example.dpop.tool_spi.AcrLevel
import com.example.dpop.tool_spi.ToolOutcome

/**
 * The reasoning [FastAccessStrategy] and [RegisterStrategy] both need for their shared states
 * ([AuthChoice], [Enrolling]) - a plain, stateless helper (same shape as [CandidateTools]), never a
 * common base class: each strategy still owns its own `transition` function and calls in here
 * explicitly, so which states an intent can produce stays fully readable from its own file.
 *
 * [resumeAtStart] exists only because [offerEnrollment] can bottom out in a RE_IDENTIFY
 * sub-journey (docs/04-orchestrierung.md, "RE_IDENTIFY") - the caller's own `Start`-like state to
 * resume at once that finishes, so a caller never has to know the OTHER caller's resume state.
 */
internal object AuthEnrollCore {

    /** State-independent: an outcome maps to one Action, full stop - what that Action then MEANS (adopt, extend, or refuse a cross-account move) is decided by its single handler from live context, never varied per caller here. */
    fun proofAction(event: JourneyEvent.Completed): Action = when (val outcome = event.outcome) {
        // The account may be brand new or an existing one found again by KVNR; both are the
        // same decision here, which is why registration needs no state of its own.
        is ToolOutcome.Completed.Identified -> Action.RecordIdentification(event.tool, outcome)
        // A real credential now exists on this device, so recognizing the device costs
        // nothing and saves the next login: bind it.
        is ToolOutcome.Completed.Enrolled -> Action.AdoptCredential(event.tool, outcome)
        // A device-bound tool never resolves the account itself - it could only have been
        // offered once the account was already known.
        is ToolOutcome.Completed.Authenticated -> Action.AcceptProof(event.tool, outcome)
        // Claims only: the account keeps the attested value (its anchor), no credential is
        // created and the device is NOT bound - unlike Enrolled above, nothing now lives on this
        // device that a later login could recognize it by.
        is ToolOutcome.Completed.Attested -> Action.AdoptAttestation(event.tool, outcome)
        is ToolOutcome.Completed.Approved -> error("${event.tool.toolId} is not offered by FAST_ACCESS/REGISTER")
    }

    /** After a proof: done, another factor via [AuthChoice], or - if nothing else can help - [offerEnrollment]. */
    fun afterProof(ctx: JourneyContext, resumeAtStart: JourneyState): Transition {
        val account = ctx.requireAccount()
        if (ctx.policy.isSatisfied(ctx.evidence, ctx.acrFloor, account)) return Transition.Authenticated

        val candidates = CandidateTools.forAuth(account, ctx.acrFloor, ctx)
        if (candidates.isNotEmpty()) return Transition.To(AuthChoice(Offer(candidates)))
        // No email obligation on this path: an existing account that merely logs in is never
        // retroactively blocked on a missing confirmed email (docs/04-orchestrierung.md #8).
        return offerEnrollment(account, ctx, emailObligation = false, resumeAtStart)
    }

    /**
     * The confirmed address comes BEFORE any enrollment (see [confirmEmail]) - so by the time this
     * runs, [emailObligation] is normally already discharged. It is re-checked here anyway as the
     * fallback for the one case the earlier offer could not cover: no attesting tool was available
     * back then (admin-disabled), in which case the obligation simply stands until one is.
     *
     * [RegisterState.ConfirmingEmail] only ever actually gets produced here when [emailObligation]
     * is true - which only [RegisterStrategy] ever passes, so this branch is simply dead code when
     * called from [FastAccessStrategy]'s own, always-`false` call sites.
     */
    fun afterEnrollment(ctx: JourneyContext, emailObligation: Boolean, resumeAtStart: JourneyState): Transition {
        val account = ctx.requireAccount()
        if (emailObligation) confirmEmail(account, ctx)?.let { return it }
        val reachable = ctx.policy.reachability(account, ctx.acrFloor) is Reachability.Reachable
        if (!reachable || !ctx.policy.isSatisfied(ctx.evidence, ctx.acrFloor, account)) {
            return offerEnrollment(account, ctx, emailObligation = false, resumeAtStart)
        }
        return Transition.Authenticated
    }

    /**
     * The confirmed address as the FIRST mandatory step of a registration, before a single
     * enrollment is offered - `null` when there is nothing to do (address already confirmed) or
     * nothing that could do it (no attesting tool available right now), so the caller falls
     * through to its own next step instead of dead-ending on an obligation nothing can fulfil.
     *
     * Ordered first because confirming stopped being an enrollment of its own: it is account
     * infrastructure (three lookup tools resolve through it, `enroll-password` is gated on it via
     * `ClaimRequirement(EMAIL, PROVEN)`), not one of the login methods competing for the user's
     * choice. Offering it after the first enrollment made the one step that UNLOCKS candidates
     * depend on candidates already chosen - the `password` knowledge factor could not even appear
     * in the first `Enrolling` offer. Same order the "Enrollment zuerst" experiment already uses
     * ([RegisterEnrollFirstStrategy.offerEmailConfirmation]).
     */
    fun confirmEmail(account: AccountProfile, ctx: JourneyContext): Transition? {
        if (account.emailConfirmed) return null
        return CandidateTools.forEmailConfirmation(ctx).takeIf { it.isNotEmpty() }
            ?.let { Transition.To(RegisterState.ConfirmingEmail(Offer(it))) }
    }

    /**
     * Never offers a NEW auth method below [ENROLLMENT_FLOOR_ACR]: a credential's `enrolledUnderAcr`
     * (ADR-5, [DefaultAuthPolicy]) permanently bakes in whatever this session had already proven at
     * the moment it was enrolled - regardless of the tool's own declared `maxAcr`. A session that
     * merely recognized the device and proved one loa1 factor (`FAST_ACCESS`'s own, identification-
     * free path, [FastAccessStrategy.firstOffer]) could otherwise enroll e.g. `enroll-device`
     * (`maxAcr=loa2`) capped at loa1 forever, with nothing surfaced until a LATER step-up
     * (`STEP_UP`/`CONFIRM_PEER_LOGIN`) confusingly rejects it as "insufficient" - a real bug report.
     * Fixed at loa2 rather than [JourneyContext.acrFloor]: this is about never handing out
     * unverified trust, not about this particular channel's own target.
     *
     * Bypassing straight to a fresh identification (never straight to failure) mirrors
     * [StepUpStrategy]'s own re-ident fallback - the one channel-independent way up from here, since
     * there is no ENROLL path that itself proves identity. Not applied to [RegisterEnrollFirstStrategy]:
     * that experiment's entire premise is enrolling BEFORE any identification exists yet (its own
     * class doc) - its capped-at-loa1 result there is the intended trade-off, not this same bug.
     */
    fun offerEnrollment(account: AccountProfile, ctx: JourneyContext, emailObligation: Boolean, resumeAtStart: JourneyState): Transition {
        if (!ctx.policy.isSatisfied(ctx.evidence, ENROLLMENT_FLOOR_ACR, account)) {
            val reidentTarget = AcrLevel.max(ENROLLMENT_FLOOR_ACR, ctx.acrFloor)
            return if (CandidateTools.forReIdentification(reidentTarget, ctx).isNotEmpty()) {
                Transition.RequireSubJourney(
                    AuthIntent.RE_IDENTIFY,
                    seedWith = ReIdentifyState.forSubJourney(reidentTarget, ctx.currentAcr),
                    resumeWith = resumeAtStart
                )
            } else {
                Transition.Abort(
                    Text("Fuer die Einrichtung eines neuen Anmeldeverfahrens ist eine frische Identifizierung (mindestens loa2) erforderlich, aktuell ist aber keine Identifizierungsmethode verfuegbar.")
                )
            }
        }

        val candidates = CandidateTools.forEnrollment(account, ctx.acrFloor, ctx)
        if (candidates.isNotEmpty()) {
            return Transition.To(Enrolling(Offer(candidates), emailObligation = emailObligation))
        }
        return if (CandidateTools.forReIdentification(ctx.acrFloor, ctx).isNotEmpty()) {
            Transition.RequireSubJourney(
                AuthIntent.RE_IDENTIFY,
                seedWith = ReIdentifyState.forSubJourney(ctx.acrFloor, ctx.currentAcr),
                resumeWith = resumeAtStart
            )
        } else {
            // Reachability.NotReachable: nothing left to enroll AND what's already active
            // genuinely can't reach the floor - that reason's own explanation applies. Reachable:
            // forEnrollment came back empty anyway for a channel-local reason (e.g. availableTools
            // disabled every remaining candidate), not an account-wide one - see
            // Reachability.toEnrollAbortMessage's own doc for the same bug in the AUTH-candidate case.
            Transition.Abort(ctx.policy.reachability(account, ctx.acrFloor).toEnrollAbortMessage())
        }
    }

    /**
     * Also the bar a REGISTER run must leave the account ABLE to reach (see
     * `RegisterStrategy.afterEnrollment`): both are the same statement - never hand out trust
     * nobody verified, and never strand an account below the level its own method management needs.
     */
    val ENROLLMENT_FLOOR_ACR = AcrLevel.LOA2

    /**
     * On a mandatory state, backing out of a tool is not declining it - the obligation
     * stands either way. So the FULL choice comes back, including the tool just abandoned: the
     * user is picking differently, not giving up. Only fallback states accumulate `declined`.
     */
    fun reoffer(state: JourneyState): Transition = Transition.To(state.withActive(null))
}
