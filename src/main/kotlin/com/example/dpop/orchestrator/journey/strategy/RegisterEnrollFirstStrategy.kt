package com.example.dpop.orchestrator.journey.strategy

import com.example.dpop.texts.Text
import com.example.dpop.account.AccountProfile
import com.example.dpop.orchestrator.journey.ANSWER_ACCEPT
import com.example.dpop.orchestrator.journey.ANSWER_DECLINE
import com.example.dpop.orchestrator.journey.Action
import com.example.dpop.orchestrator.kernel.AuthIntent
import com.example.dpop.orchestrator.journey.CandidateTools
import com.example.dpop.orchestrator.journey.IntentStrategy
import com.example.dpop.orchestrator.journey.JourneyContext
import com.example.dpop.orchestrator.journey.JourneyEvent
import com.example.dpop.orchestrator.journey.Transition
import com.example.dpop.orchestrator.journey.state.Offer
import com.example.dpop.orchestrator.journey.state.JourneyState
import com.example.dpop.orchestrator.journey.state.ReIdentifyState
import com.example.dpop.orchestrator.journey.state.RegisterEnrollFirstState
import com.example.dpop.orchestrator.journey.toEnrollAbortMessage
import com.example.dpop.orchestrator.policy.Reachability
import com.example.dpop.orchestrator.session.ChannelSession
import com.example.dpop.tool_spi.MethodRole
import com.example.dpop.tool_spi.ToolId
import com.example.dpop.tool_spi.ToolOutcome

/**
 * The "Enrollment zuerst" REGISTER experiment - see [RegisterEnrollFirstState]'s own doc for why
 * this is fully autark from [RegisterStrategy]/`AuthEnrollCore`. Mandatory order: email
 * confirmation first ([offerEmailConfirmation]/[RegisterEnrollFirstState.EnrollFirstAttestingEmail]), then SMS
 * ([offerSmsEnrollment]/[RegisterEnrollFirstState.EnrollFirstEnrollingSms]) - declining either one
 * only re-offers it, there is no skipping ahead - before the identification offer at the very end
 * ([offerIdentificationOrFinish]). Deliberately NOT a `@Component`:
 * it is never registered under [AuthIntent.REGISTER] directly, only ever reached through
 * `RegisterDispatchStrategy`, the single bean actually registered for that intent (Spring only
 * allows one `IntentStrategy` per [AuthIntent]).
 *
 * **Device rebinding is asked at the END, not where it would happen.** [RegisterStrategy] can ask
 * as soon as it identifies, because it identifies FIRST - the question then has two nameable
 * sides ("this device belongs to account X - move it to Y?"). This variant binds at its first
 * enrollment, when the account has only just been created lazily and has no identity at all, so
 * there is nothing to put in the question yet. Hence the split: nothing rebinds implicitly
 * (`JourneyActionExecutor.linkDeviceIfIntentImplies` refuses a device linked elsewhere), and
 * [finishOrOfferRebind] asks once at the very end, after the optional identification has run or
 * been declined. Declining finishes the registration without a device link - the account stays
 * fully usable through the lookup tools, it is only not recognized on this device.
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
                is JourneyEvent.SubJourneyFinished, is JourneyEvent.SubJourneyCancelled -> finishOrOfferRebind(ctx)
                // Fresh journey start: no account needed yet - it's created lazily on the first
                // completed enrollment (JourneyService's own Action.AdoptCredential handling).
                // Mandatory order: email, then SMS (see offerEmailConfirmation/offerSmsEnrollment) -
                // only once neither is available at all does this fall back to offerEnrollment's
                // old free-choice-among-everything behaviour.
                else -> offerEmailConfirmation(ctx)
            }

            is RegisterEnrollFirstState.EnrollFirstAttestingEmail -> when (event) {
                is JourneyEvent.Abandoned -> reoffer(state)
                is JourneyEvent.Completed -> Transition.Perform(adoptCredential(event), resumeState = state)
                else -> offerSmsEnrollment(ctx)
            }

            is RegisterEnrollFirstState.EnrollFirstEnrollingSms -> when (event) {
                is JourneyEvent.Abandoned -> reoffer(state)
                is JourneyEvent.Completed -> Transition.Perform(adoptCredential(event), resumeState = state)
                else -> afterForcedEnrollment(ctx)
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

            is RegisterEnrollFirstState.EnrollFirstConfirmDeviceRebind -> when (event) {
                is JourneyEvent.Answered -> when (event.answer) {
                    ANSWER_ACCEPT -> Transition.Perform(Action.LinkDevice, resumeState = state)
                    // Declining costs the registration nothing: the account stays fully usable
                    // through the lookup tools, it just will not be recognized on this device.
                    ANSWER_DECLINE -> Transition.Authenticated
                    else -> error("EnrollFirstConfirmDeviceRebind does not understand answer '${event.answer}'")
                }
                is JourneyEvent.ActionCompleted -> Transition.Authenticated
                else -> error("EnrollFirstConfirmDeviceRebind only accepts JourneyEvent.Answered")
            }

            is RegisterEnrollFirstState.EnrollFirstPasswordObligation -> when (event) {
                is JourneyEvent.Abandoned -> reoffer(state)
                is JourneyEvent.Completed -> Transition.Perform(adoptCredential(event), resumeState = state)
                // Reached only once the email obligation (if any) already discharged, same
                // ordering reasoning as RegisterState.PasswordObligation's own KDoc.
                else -> afterEnrollment(ctx, emailObligation = false)
            }
        }

    private fun adoptCredential(event: JourneyEvent.Completed): Action = when (val outcome = event.outcome) {
        is ToolOutcome.Completed.Enrolled -> Action.AdoptCredential(event.tool, outcome)
        // The mandatory first step confirms the address: claims and anchor, no credential, and no
        // device binding - nothing was created here this device could later be recognized by.
        is ToolOutcome.Completed.Attested -> Action.AdoptAttestation(event.tool, outcome)
        else -> error("${event.tool.toolId} is not offered by REGISTER (Enrollment zuerst) - only ENROLLMENT and ATTESTATION tools ever are")
    }

    /**
     * Mandatory step 1: CONFIRM the address, which is account infrastructure (three lookup tools
     * resolve through it, `enroll-password` is gated on it) - not enrolling email as a login
     * method, which stays optional. Falls through to step 2 if no attesting tool is available at
     * all right now.
     */
    private fun offerEmailConfirmation(ctx: JourneyContext): Transition {
        val candidates = CandidateTools.forEmailConfirmation(ctx)
        return if (candidates.isNotEmpty()) Transition.To(RegisterEnrollFirstState.EnrollFirstAttestingEmail(Offer(candidates))) else offerSmsEnrollment(ctx)
    }

    /** Mandatory step 2 - falls through to [afterForcedEnrollment] if no SMS-method ENROLLMENT tool is available at all right now. */
    private fun offerSmsEnrollment(ctx: JourneyContext): Transition {
        val candidates = enrollmentCandidatesFor(SMS_METHOD, ctx)
        return if (candidates.isNotEmpty()) Transition.To(RegisterEnrollFirstState.EnrollFirstEnrollingSms(Offer(candidates))) else afterForcedEnrollment(ctx)
    }

    /**
     * Both mandatory steps are discharged or skipped (neither tool was available). If an account
     * already exists (an enrollment actually happened), continue the normal obligation cascade. If
     * neither email nor SMS was ever available, no account exists yet - fall back to the old
     * free-choice-among-everything offer instead of crashing on `ctx.requireAccount()`.
     */
    private fun afterForcedEnrollment(ctx: JourneyContext): Transition =
        if (ctx.account != null) afterEnrollment(ctx, emailObligation = true) else offerEnrollment(ctx)

    private fun offerEnrollment(ctx: JourneyContext): Transition {
        // No account exists yet at this point (see EnrollFirstStart's own doc) - enrollmentCandidates
        // only ever reads authenticationMethods/emailConfirmed, both trivially empty/false for a
        // brand-new account, so a transient, unpersisted placeholder is enough.
        val blankAccount = AccountProfile(accountId = -1, personId = null, authenticationMethods = emptyList())
        val candidates = CandidateTools.forEnrollment(blankAccount, ctx.acrFloor, ctx)
        return if (candidates.isNotEmpty()) {
            Transition.To(RegisterEnrollFirstState.EnrollFirstEnrolling(Offer(candidates)))
        } else {
            Transition.Abort(Text("Kein Anmeldeverfahren verfuegbar"))
        }
    }

    /**
     * Mirrors `AuthEnrollCore.afterEnrollment`'s exact cascade (sufficient method -> confirmed
     * email -> password), just self-contained and, once every obligation is discharged,
     * ending in the optional identification offer instead of `Transition.Authenticated` directly.
     */
    private fun afterEnrollment(ctx: JourneyContext, emailObligation: Boolean): Transition {
        val account = ctx.requireAccount()
        val reachability = ctx.policy.reachability(account, ctx.acrFloor)
        if (reachability !is Reachability.Reachable || !ctx.policy.isSatisfied(ctx.evidence, ctx.acrFloor, account)) {
            val candidates = CandidateTools.forEnrollment(account, ctx.acrFloor, ctx)
            return if (candidates.isNotEmpty()) {
                Transition.To(RegisterEnrollFirstState.EnrollFirstEnrolling(Offer(candidates)))
            } else {
                // Reachability.NotReachable: that reason's own explanation applies. Reachable:
                // forEnrollment came back empty anyway for a channel-local reason (e.g.
                // availableTools disabled every remaining candidate), not an account-wide one -
                // see Reachability.toEnrollAbortMessage's own doc for the same bug.
                Transition.Abort(reachability.toEnrollAbortMessage())
            }
        }
        if (emailObligation && !account.emailConfirmed) {
            CandidateTools.forEmailConfirmation(ctx).takeIf { it.isNotEmpty() }
                ?.let { return Transition.To(RegisterEnrollFirstState.EnrollFirstConfirmingEmail(Offer(it))) }
        }
        // Same rule as RegisterStrategy.afterEnrollment (ADR-17): a password only where the account
        // could not otherwise get back to loa2 on its own. Here that is always the case unless the
        // optional identification already ran: every method enrolled without it sits at loa1, and
        // the two-factor bump is capped by enrolledUnderAcr (ADR-5). One rule, not two readings.
        if (ctx.policy.reachability(account, AuthEnrollCore.ENROLLMENT_FLOOR_ACR) !is Reachability.Reachable &&
            account.activeAuthenticationMethods.none { it.method == PASSWORD_METHOD }
        ) {
            passwordEnrollmentCandidates(ctx).takeIf { it.isNotEmpty() }
                ?.let { return Transition.To(RegisterEnrollFirstState.EnrollFirstPasswordObligation(Offer(it))) }
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
                // Every obligation is already discharged at this point (see this fn's own doc) -
                // RE_IDENTIFY's default wording assumes the opposite ("nicht erreichbar", "erneut"),
                // which is factually wrong for a brand-new, never-identified account. Own wording,
                // not the shared default.
                seedWith = ReIdentifyState.forSubJourney(
                    targetAcr = ctx.acrFloor,
                    startingAcr = ctx.currentAcr,
                    wording = ReIdentifyState.Wording.OPTIONAL_IDENTIFICATION
                ),
                resumeWith = RegisterEnrollFirstState.EnrollFirstStart
            )
        } else {
            finishOrOfferRebind(ctx)
        }

    /**
     * The last question of this journey, and deliberately the last: this device is durably linked
     * to ANOTHER account, so nothing bound it implicitly on the way here
     * (`JourneyActionExecutor.linkDeviceIfIntentImplies` never rebinds - see
     * [RegisterEnrollFirstState.EnrollFirstConfirmDeviceRebind]). Asked here rather than at the
     * first enrollment, where the binding would otherwise have happened, because by now the
     * optional identification has run or been declined - at that earlier point the account had
     * just been created lazily and had no identity to put in the question at all.
     *
     * Nothing to ask on a KEYCLOAK channel: it has no device, so `linkedAccountId` is null there
     * and this falls straight through.
     */
    private fun finishOrOfferRebind(ctx: JourneyContext): Transition {
        val accountId = ctx.account?.accountId
        val linkedElsewhere = ctx.linkedAccountId != null && ctx.linkedAccountId != accountId
        return if (accountId != null && linkedElsewhere) {
            Transition.To(RegisterEnrollFirstState.EnrollFirstConfirmDeviceRebind)
        } else {
            Transition.Authenticated
        }
    }

    /** ENROLLMENT-role tools for one hardcoded method name, e.g. "email"/"sms"/"password" - same reasoning as `RegisterStrategy.passwordEnrollmentCandidates`. */
    private fun enrollmentCandidatesFor(method: String, ctx: JourneyContext): List<ToolId> =
        ctx.catalog.descriptors()
            .filter { it.role == MethodRole.ENROLLMENT && it.method == method }
            .map { it.toolId }
            .filter { it in ctx.availableTools }

    private fun passwordEnrollmentCandidates(ctx: JourneyContext): List<ToolId> = enrollmentCandidatesFor(PASSWORD_METHOD, ctx)

    /** Every state here is mandatory (no obligation is ever narrowed by decline) - backing out re-offers the full set, same reasoning as `AuthEnrollCore.reoffer`. */
    private fun reoffer(state: JourneyState): Transition = Transition.To(state.withActive(null))

    private companion object {
        const val PASSWORD_METHOD = "password"
        const val EMAIL_METHOD = "email"
        const val SMS_METHOD = "sms"
    }
}
