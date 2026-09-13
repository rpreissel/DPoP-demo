package com.example.dpop.orchestrator.journey.strategy

import com.example.dpop.account.AccountProfile
import com.example.dpop.orchestrator.journey.Action
import com.example.dpop.orchestrator.journey.AuthIntent
import com.example.dpop.orchestrator.journey.CandidateTools
import com.example.dpop.orchestrator.journey.IntentStrategy
import com.example.dpop.orchestrator.journey.JourneyContext
import com.example.dpop.orchestrator.journey.JourneyEvent
import com.example.dpop.orchestrator.journey.Transition
import com.example.dpop.orchestrator.journey.state.JourneyState
import com.example.dpop.orchestrator.journey.state.ReIdentifyState
import com.example.dpop.orchestrator.journey.state.RegisterEnrollFirstState
import com.example.dpop.orchestrator.session.ChannelSession
import com.example.dpop.orchestrator.session.ChannelState
import com.example.dpop.tool_spi.MethodRole
import com.example.dpop.tool_spi.ToolOutcome

/**
 * The "Enrollment zuerst" REGISTER experiment - see [RegisterEnrollFirstState]'s own doc for why
 * this is fully autark from [RegisterStrategy]/`AuthEnrollCore`. Deliberately NOT a `@Component`:
 * it is never registered under [AuthIntent.REGISTER] directly, only ever reached through
 * `RegisterDispatchStrategy`, the single bean actually registered for that intent (Spring only
 * allows one `IntentStrategy` per [AuthIntent]).
 */
class RegisterEnrollFirstStrategy : IntentStrategy<RegisterEnrollFirstState> {

    override val intent: AuthIntent = AuthIntent.REGISTER

    override fun initialState(ctx: JourneyContext): RegisterEnrollFirstState = RegisterEnrollFirstState.EnrollFirstStart

    override fun transition(state: RegisterEnrollFirstState, event: JourneyEvent, ctx: JourneyContext): Transition =
        when (state) {
            is RegisterEnrollFirstState.EnrollFirstStart -> when (event) {
                // The closing, optional RE_IDENTIFY offer (see offerIdentificationOrFinish) came
                // back - accepted-and-succeeded, declined, or abandoned: either way, this journey
                // is done. A later identification remains reachable at any time via a step-up's
                // own RE_IDENTIFY, this is not the only chance.
                is JourneyEvent.SubJourneyFinished, is JourneyEvent.SubJourneyCancelled -> Transition.Authenticated
                // Fresh journey start: no account needed yet - it's created lazily on the first
                // completed enrollment (JourneyService's own Action.AdoptCredential handling), so
                // the offer here is computed against a transient, unpersisted placeholder.
                // enrollmentCandidates only ever reads authenticationMethods/emailConfirmed, both
                // trivially empty/false for a brand-new account.
                else -> offerEnrollment(ctx)
            }

            is RegisterEnrollFirstState.EnrollFirstEnrolling -> when (event) {
                is JourneyEvent.Abandoned -> reoffer(state)
                is JourneyEvent.Completed -> Transition.Perform(adoptCredential(event), resumeState = state)
                else -> afterEnrollment(ctx, emailObligation = true)
            }

            is RegisterEnrollFirstState.EnrollFirstConfirmingEmail -> when (event) {
                is JourneyEvent.Abandoned -> reoffer(state)
                is JourneyEvent.Completed -> Transition.Perform(adoptCredential(event), resumeState = state)
                else -> afterEnrollment(ctx, emailObligation = false)
            }

            is RegisterEnrollFirstState.EnrollFirstPasswordObligation -> when (event) {
                is JourneyEvent.Abandoned -> reoffer(state)
                is JourneyEvent.Completed -> Transition.Perform(adoptCredential(event), resumeState = state)
                // Reached only once the email obligation (if any) already discharged, same
                // ordering reasoning as RegisterState.PasswordObligation's own KDoc.
                else -> afterEnrollment(ctx, emailObligation = false)
            }
        }

    override fun cancelledTo(state: RegisterEnrollFirstState): ChannelState = ChannelState.ANONYMOUS

    private fun adoptCredential(event: JourneyEvent.Completed): Action = when (val outcome = event.outcome) {
        is ToolOutcome.Completed.Enrolled -> Action.AdoptCredential(event.tool, outcome, bindDevice = true)
        else -> error("${event.tool.toolId} is not offered by REGISTER (Enrollment zuerst) - only ENROLLMENT tools ever are")
    }

    private fun offerEnrollment(ctx: JourneyContext): Transition {
        // No account exists yet at this point (see EnrollFirstStart's own doc) - enrollmentCandidates
        // only ever reads authenticationMethods/emailConfirmed, both trivially empty/false for a
        // brand-new account, so a transient, unpersisted placeholder is enough.
        val blankAccount = AccountProfile(accountId = -1, personId = null, identifications = emptyList(), authenticationMethods = emptyList())
        val candidates = CandidateTools.forEnrollment(blankAccount, ctx.acrFloor, ctx)
        return if (candidates.isNotEmpty()) {
            Transition.To(RegisterEnrollFirstState.EnrollFirstEnrolling(candidates))
        } else {
            Transition.Abort("Kein Anmeldeverfahren verfuegbar")
        }
    }

    /**
     * Mirrors `AuthEnrollCore.afterEnrollment`'s exact cascade (sufficient method -> confirmed
     * email -> KEYCLOAK password), just self-contained and, once every obligation is discharged,
     * ending in the optional identification offer instead of `Transition.Authenticated` directly.
     */
    private fun afterEnrollment(ctx: JourneyContext, emailObligation: Boolean): Transition {
        val account = ctx.requireAccount()
        val reachable = ctx.policy.canAccountReach(account, ctx.acrFloor)
        if (!reachable || !ctx.policy.isSatisfied(ctx.evidence, ctx.acrFloor, account)) {
            val candidates = CandidateTools.forEnrollment(account, ctx.acrFloor, ctx)
            return if (candidates.isNotEmpty()) {
                Transition.To(RegisterEnrollFirstState.EnrollFirstEnrolling(candidates))
            } else {
                Transition.Abort("Gefordertes Sicherheitsniveau ist mit den vorhandenen Methoden nicht erreichbar. ${ctx.policy.unreachableReason(account, ctx.acrFloor)}")
            }
        }
        if (emailObligation && !account.emailConfirmed) {
            CandidateTools.forEmailConfirmation(ctx).takeIf { it.isNotEmpty() }
                ?.let { return Transition.To(RegisterEnrollFirstState.EnrollFirstConfirmingEmail(it)) }
        }
        if (ctx.channel == ChannelSession.Channel.KEYCLOAK && account.activeAuthenticationMethods.none { it.method == PASSWORD_METHOD }) {
            passwordEnrollmentCandidates(ctx).takeIf { it.isNotEmpty() }
                ?.let { return Transition.To(RegisterEnrollFirstState.EnrollFirstPasswordObligation(it)) }
        }
        return offerIdentificationOrFinish(ctx)
    }

    /**
     * Every enrollment obligation is discharged - identification is offered once, optionally, via
     * the pre-existing RE_IDENTIFY sub-journey (its own `OfferReIdent` prompt already asks
     * "Erneut identifizieren?"); declining or having nothing to offer there both simply finish the
     * registration (see [RegisterEnrollFirstState.EnrollFirstStart]'s own `SubJourneyFinished`/`Cancelled` arm).
     */
    private fun offerIdentificationOrFinish(ctx: JourneyContext): Transition =
        if (CandidateTools.forReIdentification(ctx.acrFloor, ctx).isNotEmpty()) {
            Transition.RequireSubJourney(
                AuthIntent.RE_IDENTIFY,
                seedWith = ReIdentifyState.forSubJourney(targetAcr = ctx.acrFloor, startingAcr = ctx.currentAcr),
                resumeWith = RegisterEnrollFirstState.EnrollFirstStart
            )
        } else {
            Transition.Authenticated
        }

    /** Same hardcoded METHOD-not-toolId reasoning as `RegisterStrategy.passwordEnrollmentCandidates`. */
    private fun passwordEnrollmentCandidates(ctx: JourneyContext): List<String> =
        ctx.catalog.descriptors()
            .filter { it.role == MethodRole.ENROLLMENT && it.method == PASSWORD_METHOD }
            .map { it.toolId }
            .filter { it in ctx.availableTools }

    /** Every state here is mandatory (no obligation is ever narrowed by decline) - backing out re-offers the full set, same reasoning as `AuthEnrollCore.reoffer`. */
    private fun reoffer(state: JourneyState): Transition = Transition.To(state.withActive(null))

    private companion object {
        const val PASSWORD_METHOD = "password"
    }
}
