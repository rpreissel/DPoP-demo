package com.example.dpop.orchestrator.journey

import com.example.dpop.texts.Text
import com.example.dpop.orchestrator.kernel.ChannelType
import com.example.dpop.account.AccountProfile
import com.example.dpop.orchestrator.journey.state.JourneyState
import com.example.dpop.orchestrator.journey.state.OfferingState
import com.example.dpop.orchestrator.policy.AuthEvidence
import com.example.dpop.orchestrator.policy.AuthPolicy
import com.example.dpop.orchestrator.policy.MethodEvidence
import com.example.dpop.orchestrator.kernel.AcrLevels
import com.example.dpop.orchestrator.session.ChannelState
import com.example.dpop.orchestrator.tool.ToolHandlerRegistry
import com.example.dpop.tool_spi.AcrLevel
import com.example.dpop.tool_spi.AttributeType
import com.example.dpop.tool_spi.ToolDescriptor
import com.example.dpop.tool_spi.ToolId
import com.example.dpop.tool_spi.ToolOutcome
import com.example.dpop.orchestrator.kernel.AuthIntent
import com.example.dpop.orchestrator.kernel.FeatureFlags

/**
 * The ACR floor for any action that only ever touches an account's OWN credentials/data, never a
 * stranger's - anti-self-escalation (a hijacked loa1 session must not act as if it had proved
 * more than it did) shared by every such gate ([Action.DeleteAccount.requiredAcr],
 * `ManageAuthMethodsStrategy.gate`) so the rule has exactly one definition instead of a
 * per-strategy constant.
 *
 * loa1 suffices for an account that was never identified ([AccountProfile.personId] `== null` -
 * the "Enrollment zuerst" case, docs/04-orchestrierung.md REGISTER): there is no bound
 * identity/Stammdaten a hijacked loa1 session could reach beyond what the enrollment itself
 * already exposed. Every identified account still requires loa2.
 *
 * Keyed on `personId`, deliberately not on the account's current `acr`: a never-identified
 * account can in fact never reach loa2 in the first place (`DefaultAuthPolicy.combinedAcr` caps
 * any MFA bump at the highest `enrolledUnderAcr` among its methods, and every method such an
 * account enrolls is itself capped at loa1 - there is no path to a higher `enrolledUnderAcr`
 * without an identification first). This floor therefore never grants more than such an account
 * could legitimately reach anyway; it only stops demanding a level it could never clear.
 */
fun selfServiceAcrFloor(account: AccountProfile?): AcrLevel =
    if (account?.personId == null) AcrLevels.DEFAULT_REQUIRED_ACR else AcrLevel.LOA2

/**
 * The SPI each intent implements - symmetric to `tool_spi`, where tools describe themselves.
 *
 * A strategy DECIDES, it never ACTS: it gets a read-only [JourneyContext] and names an [Action]
 * for JourneyService to execute. Everything with a side effect - creating accounts, recording
 * evidence, writing device links, capping ACR - is executed centrally by [JourneyService] and is
 * not reachable from here.
 *
 * `transition` is the transition function (classical automaton terminology: `δ`) - the one and
 * only place a strategy answers "what happens next". "First offer", "a tool just completed", "a
 * tool was abandoned", "an action just finished" and "a sub-journey came back" are all the same
 * question with a different [JourneyEvent], not four separate methods.
 */
interface IntentStrategy<S : JourneyState> {
    /** Which [AuthIntent] this strategy implements - one bean per entry in that enum. */
    val intent: AuthIntent

    /** Where a fresh journey of this intent begins, before any event has been seen. */
    fun initialState(ctx: JourneyContext): S

    /**
     * The one and only transition. A completed tool's outcome answers "what did it establish" via
     * [Transition.Perform]: JourneyService executes the returned [Action], refreshes
     * [JourneyContext] from the result, and calls this again with [JourneyEvent.ActionCompleted] -
     * so a strategy's own follow-up logic (e.g. "is the floor satisfied now?") always sees the
     * POST-action context, never the stale one the tool outcome arrived with.
     */
    fun transition(state: S, event: JourneyEvent, ctx: JourneyContext): Transition

    /** Which channel state a cancelled journey of this intent falls back to. */
    fun cancelledTo(state: S): ChannelState
}

/**
 * Everything a strategy may look at. Read-only by construction: [policy] and [catalog] answer
 * questions, they change nothing.
 */
data class JourneyContext(
    /**
     * Which facade opened this channel (docs/02-domaenenmodell.md #4). Named explicitly rather
     * than inferred from [bindingKeyRef] being null - a strategy that needs to branch on channel
     * type (e.g. a Web-only enrollment obligation) should read a declared fact, not an implicit
     * side effect of a different, APP-only concept.
     */
    val channel: ChannelType,
    /** The account this journey concerns, once resolved - `null` before any identification/lookup. */
    val account: AccountProfile?,
    /** What this channel's session has already proven. */
    val evidence: AuthEvidence,
    /** The channel's durable lower bound - never a single run's target (that lives in the state). */
    val acrFloor: AcrLevel,
    /** The calling device's DPoP-proven key thumbprint - null on a KEYCLOAK channel, which has none. */
    val bindingKeyRef: String?,
    /** The account this device is durably linked to, if any - independent of this channel. */
    val linkedAccountId: Long?,
    /** True while this journey runs as another one's precondition (docs/04-orchestrierung.md #6). */
    val isSubJourney: Boolean,
    /** Answers ACR/candidate questions - see [AuthPolicy]. */
    val policy: AuthPolicy,
    /** The full tool catalog, for descriptor lookups. */
    val catalog: ToolHandlerRegistry,
    /**
     * toolIds this channel may currently offer: the client's own declared support intersected with
     * whatever the backend hasn't killed-switched off (docs/03-tool-architektur.md, availability).
     * [CandidateTools] filters every candidate list through this - never derive an offer from
     * [catalog] alone.
     */
    val availableTools: Set<ToolId>,
    /**
     * Runtime feature flags currently enabled (see [FeatureFlags] for the known names), resolved
     * once here rather than injected into a strategy directly - a strategy DECIDES, it never
     * depends on a `@Service` itself (`IntentStrategy`'s own class doc, enforced by
     * `OrchestratorArchitectureTest`). One shared, generic set rather than a new named
     * `JourneyContext` property per flag, so adding a future experiment never touches this
     * widely-used data class again.
     */
    val featureFlags: Set<String> = emptySet()
) {
    /**
     * The resolved account, for the states that structurally cannot be reached without one (a
     * step-up, a method change, a deletion). Crashes rather than returning `null`, because at
     * those states a missing account is a broken state machine, not a case to branch on - a
     * strategy that must tolerate its absence reads [account] directly instead.
     */
    fun requireAccount(): AccountProfile =
        checkNotNull(account) { "Strategy asked for an account before one was resolved" }

    /**
     * The ACR this channel's evidence resolves to right now - convenience for a strategy building
     * a [Transition.RequireSubJourney]'s own `seedWith` (the sub-journey's `startingAcr`), so every
     * such call site doesn't have to repeat `policy.resolveAcr(evidence, account)` itself.
     */
    val currentAcr: AcrLevel get() = policy.resolveAcr(evidence, account)
}

/** What just happened to the journey. */
sealed interface JourneyEvent {
    /** The journey was just created and has to produce its first offer. */
    data object Started : JourneyEvent

    /**
     * Evidence was reported directly, outside any orchestrator tool outcome - e.g. Keycloak's own
     * native authenticators (docs/05-api.md Abschnitt 3), the only
     * source today. The channel's [com.example.dpop.orchestrator.session.AuthEvidence] was already
     * updated with it by the time this fires, so a strategy only needs to re-check `ctx.policy.
     * isSatisfied(...)`, exactly like after any other proof. Facade-neutral by construction - only
     * ever dispatched by `JourneyService.applyEvidenceUpdate`, which itself knows nothing about
     * Keycloak (the caller supplies `source` explicitly); an intent no such caller ever reaches
     * (e.g. anything APP-only) simply never receives it.
     */
    data object EvidenceReported : JourneyEvent

    /** A tool finished successfully; [outcome] is what a strategy turns into an [Action]. */
    data class Completed(val tool: ToolDescriptor, val outcome: ToolOutcome.Completed) : JourneyEvent

    /** "Back"/"Switch": the user abandoned an activated tool without finishing it. */
    data class Abandoned(val tool: ToolDescriptor) : JourneyEvent

    /**
     * A [Transition.Perform]'s [Action] just finished executing, against a freshly derived
     * [JourneyContext] - see [IntentStrategy.transition]'s own doc. Every state a strategy names
     * as [Transition.Perform.resumeState] must have an arm for this event: it is the only event
     * that state will ever actually see next.
     */
    data object ActionCompleted : JourneyEvent

    /**
     * [intent] names WHICH sub-journey just finished - a resumed parent must never assume this by
     * construction ("only one caller today"), because a future second [Transition.RequireSubJourney]
     * from the same state would then silently be mistaken for the first. `DeleteAccountStrategy`'s
     * `ConfirmPending` branch is the one consumer that actually checks it.
     *
     * A genuine finish only - see [SubJourneyCancelled] for the sub-journey being abandoned
     * instead. Kept as two distinct types rather than one plus a boolean: a resumed `Start`-like
     * state that blindly re-derives its own next step from [achievedAcr]/evidence alone, without
     * even looking at which of the two happened, would silently re-request the very same
     * sub-journey it was just declined - the identical confirm prompt forever. Two `when` arms the
     * compiler can force every consumer to cover beats a flag a consumer can simply forget to read.
     */
    data class SubJourneyFinished(val intent: AuthIntent, val achievedAcr: AcrLevel?) : JourneyEvent

    /**
     * The sub-journey was abandoned - RE_IDENTIFY's offer declined, or every tool it offered
     * abandoned - without achieving anything, so no [achievedAcr] to report (there is nothing new
     * to re-check the caller's target against). [intent] names WHICH one, same reasoning as
     * [SubJourneyFinished.intent].
     */
    data class SubJourneyCancelled(val intent: AuthIntent) : JourneyEvent

    /**
     * An explicit answer to whatever an [AnswerableState] is waiting on, instead of a tool run -
     * see [JourneyService.answer]. [answer] is a plain string, not a boolean: today's only case is
     * accept/decline, but nothing here should have to change the day some future action needs more
     * than two choices - the owning intent's own [IntentStrategy.transition] alone decides which
     * values are valid.
     */
    data class Answered(val answer: String) : JourneyEvent
}

/**
 * What should happen next. Deliberately NOT a `Next` - the skip-if-single-candidate rule and the
 * routing derivation live once in [JourneyService], not once per intent.
 *
 * There is no separate "offer these tools" variant: a state already carries what it offers
 * ([JourneyState.activatable]), so [To] to that state IS the offer.
 */
sealed interface Transition {
    /** Move the journey to [state]; what it now offers is read straight off that state. */
    data class To(val state: JourneyState) : Transition

    /**
     * Run [intent] first, seeded at [seedWith] (the requesting strategy builds this itself via
     * that intent's own state's companion factory, e.g. `StepUpState.forSubJourney(...)` - same
     * idiom as [resumeWith] already builds ITS OWN journey's continuation state directly), then
     * resume this journey at [resumeWith] once it finishes.
     */
    data class RequireSubJourney(
        val intent: AuthIntent,
        val seedWith: JourneyState,
        val resumeWith: JourneyState
    ) : Transition {
        init {
            // [resumeWith] is persisted and only reactivated once the whole sub-journey has run -
            // minutes later, with new evidence, possibly a different account and different active
            // methods. A candidate list frozen into it would be re-offered as if it were current;
            // worse, the sub-journey exists precisely BECAUSE the situation was insufficient, so
            // its result is the one thing that must be re-read. Resume at a state that recomputes
            // (a Start-like one), never at an offer.
            //
            // Enforced rather than documented: "every caller gets it right" is not a property a
            // safety rule may rest on, however true it happens to be right now.
            check(resumeWith !is OfferingState) {
                "resumeWith must not be an OfferingState (${resumeWith::class.simpleName}): a sub-journey's " +
                    "whole point is that the situation changed, so the offer has to be recomputed on return"
            }
        }
    }

    /** Goal reached: consume the journey, the channel becomes AUTHENTICATED. */
    data object Authenticated : Transition

    /**
     * The user gave up (abandoned the last thing this journey could offer). Distinct from
     * [Abort]: nothing went wrong, so this ends like an explicit cancel - via
     * [IntentStrategy.cancelledTo], with a fresh start offered afterwards - rather than as a 410.
     */
    data object Cancel : Transition

    /**
     * Run [action], then resume the journey at [resumeState] with [JourneyEvent.ActionCompleted]
     * once it has - the state a completed tool outcome or a strategy's own action (deactivating a
     * method, linking a device, deleting an account) continues at afterward.
     */
    data class Perform(val action: Action, val resumeState: JourneyState) : Transition

    /**
     * Confirmed logout: ends the channel for good. The journey is consumed, authContext
     * discarded, channel becomes LOGGED_OUT. Also the natural resume target after
     * [Action.DeleteAccount].
     */
    data object Logout : Transition

    /** No way forward at all. Ends the journey with 410 - never a mere "no candidates left". */
    data class Abort(val reason: Text) : Transition
}

/**
 * A named side effect a strategy decided, for [JourneyService] to actually execute - the strategy
 * never acts itself (see [IntentStrategy]'s own class doc). Two origins share this one
 * vocabulary: the first three variants answer "what did a just-completed tool establish" (carried
 * by a [Transition.Perform] returned in reaction to [JourneyEvent.Completed], with the very
 * [ToolDescriptor]/[ToolOutcome] that arrived with it); the rest are a strategy's own actions,
 * likewise wrapped in [Transition.Perform].
 *
 * **Trust levels, made explicit on purpose:** [RecordIdentification] and [AdoptAttestation] are
 * the only two variants whose account resolution can ever land on an account OTHER than the one
 * already bound to this journey/channel - every strategy that constructs either one funnels
 * through the exact same gate in `JourneyActionExecutor` (`accountOf`, called from
 * `performRecordIdentification` and `performAdoptAttestation` alike), never a per-caller
 * reimplementation. There is deliberately no pair of variants a strategy could pick between to
 * pick which safety rule applies: which account a resolution may land on is a property of the
 * EVIDENCE (tool category, `EvidenceAxis`), never of which code path happened to call it. Every
 * other variant below only ever acts on the account already known from context - structurally
 * incapable of crossing to a different one, not merely by convention.
 */
sealed interface Action {
    /**
     * A completed IDENTIFICATION ([MethodRole.category] `IDENT`, e.g. `ident-fsc`/`ident-eid`) or
     * CORRELATION (`ident-kvnr`) tool resolved (or extended) an identity. One handler
     * (`performRecordIdentification`) for both origins: whether an account is already known from
     * context is read from the journey/channel at execution time, never pre-decided by the caller
     * - so no strategy can construct "the version that skips the merge-safety check".
     */
    data class RecordIdentification(val tool: ToolDescriptor, val outcome: ToolOutcome.Completed.Identified) : Action

    /**
     * An attribute the ACCOUNT owns was attested (e.g. a confirmed email address): record the
     * claims, materialize the anchor - but create no method instance and bind no device. The
     * counterpart to [AdoptCredential] for a value that is account infrastructure rather than a
     * credential (docs/12-entscheidungen.md, `AttributeType.authority`).
     *
     * Deliberately weaker than [RecordIdentification]: an attestation alone can extend the account already
     * in hand, but may only land on a DIFFERENT existing account when this session has already
     * proven [RecordIdentification] evidence this same journey (`EvidenceAxis.IDENTITY` - checked
     * inside `performAdoptAttestation`, not left to the caller). Possession of a mailbox is never,
     * by itself, proof of who owns the account that mailbox is already confirmed on.
     */
    data class AdoptAttestation(val tool: ToolDescriptor, val outcome: ToolOutcome.Completed.Attested) : Action

    /**
     * A new credential was enrolled. Whether this also links the device is NOT a field here:
     * it follows from the journey's own [AuthIntent.bindsDeviceImplicitly] (and, for a KEYCLOAK
     * channel, from there being no device at all).
     */
    data class AdoptCredential(
        val tool: ToolDescriptor,
        val outcome: ToolOutcome.Completed.Enrolled
    ) : Action

    /**
     * A credential was proven. Whether the tool is allowed to NAME the account it proved (a
     * lookup login, where nothing else could know it yet) is deliberately NOT a field here: it is
     * derived in `performAcceptProof` from [MethodRole.LOOKUP_AUTH] - the one role whose whole
     * contract is "resolves the account itself from a submitted identifier" - plus the live
     * journey/channel binding, and a named account that disagrees with one already bound is a
     * `409`, never a silent switch. Not a flag a strategy passes: the safety of "a tool may not
     * name any account it likes" must not rest on every caller getting it right, nor on a snapshot
     * computed when the state was built rather than when the action runs.
     */
    data class AcceptProof(
        val tool: ToolDescriptor,
        val outcome: ToolOutcome.Completed.Authenticated
    ) : Action

    /**
     * Prime a fresh channel's evidence from [methods] before this journey's own first decision -
     * the Anfangs-Übergang ([JourneyService.start]'s `seedAction`, docs/04-orchestrierung.md,
     * "RestoreData als erster Übergang"), never a strategy's own decision: no strategy ever sees this
     * action, it is applied mechanically before `initialState()` even runs.
     */
    data class ApplyRestoredEvidence(
        /**
         * Which foreign system vouches for [methods] (e.g. `AmrSource.KEYCLOAK`) - evidence is
         * merged per source, so a later report from the same one replaces its own set rather than
         * accumulating alongside it.
         */
        val source: String,
        /**
         * The COMPLETE current set from [source], never a delta: every caller re-reports
         * everything it knows on every call (docs/05-api.md Abschnitt 3).
         */
        val methods: List<MethodEvidence>
    ) : Action

    /**
     * A [ToolOutcome.Completed.Approved] tool approved a peer channel's pending request. The
     * approval's own domain effect (e.g. writing `QrLoginRequest`) already happened inside the
     * tool itself before it reported [ToolOutcome.Completed.Approved] - this action only carries
     * the outcome through the same MethodEvidence bookkeeping every other tool-outcome action gets
     * ([JourneyService.recordToolCompletion]), for consistency and auditing, not because anything
     * about THIS channel's own evidence actually changed.
     */
    data class RecordApproval(val tool: ToolDescriptor, val outcome: ToolOutcome.Completed.Approved) : Action

    /**
     * Revoke ONE authentication method of the account this session holds - the credential itself
     * goes, not just an active flag (`AccountDeletionService.revokeMethod`). Not a tool run; the
     * strategy decides it, the machine executes it (rejecting self-lockout).
     *
     * Named for what it destroys, next to [DeleteAccount] which destroys the whole account: the
     * two are deliberately not near-synonyms ("Remove" said neither what was removed nor how it
     * differed from deleting everything). Names the method, never the account: which account that
     * instance has to belong to is read from the live session, so a stale or wrong id cannot
     * reach into another account's methods.
     */
    data class RevokeAuthMethod(val methodInstanceId: String) : Action

    /**
     * Withdraw one account attribute - today only a confirmed address. Its own action rather
     * than a variant of [RevokeAuthMethod] because it destroys something else entirely: an
     * account-owned fact, not a credential. What DID depend on it falls as a consequence, worked
     * out at execution time from the catalog's own `requires` declarations, never listed here
     * (`JourneyActionExecutor.performRetractAttribute`).
     */
    data class RetractAttribute(val attributeType: AttributeType) : Action

    /**
     * Link the current device to the account this session holds - the action an accepted
     * device-binding offer asks for (see [JourneyEvent.Answered]).
     *
     * Deliberately carries NO accountId - an id a strategy stored in its own state would be
     * persisted in the journey's JSON when that state was built and read back only when the user
     * finally answers. Binding a physical device is the single most durable thing this machine
     * does (`DeviceAccountLink` outlives every journey and sends the next `FAST_ACCESS` straight
     * into that account), so it must follow the session's own current binding.
     */
    data object LinkDevice : Action

    /**
     * Delete the account this session holds, and everything it owns. An irreversible action, so
     * `JourneyActionExecutor` independently re-checks [requiredAcr] against the CURRENT evidence
     * right before executing it, exactly like it independently re-checks self-lockout before
     * [RevokeAuthMethod].
     *
     * Carries no accountId for the same reason [LinkDevice] does not - and here it was outright
     * inconsistent: the permission check ran against the session's account while the deletion
     * targeted the action's own field, so the two could name different accounts.
     */
    data object DeleteAccount : Action {
        /**
         * The ACR JourneyService independently re-checks right before executing this action.
         * Lives here rather than in `DeleteAccountStrategy` so the generic machine can
         * reference it without importing a concrete [IntentStrategy] implementation. Deleting
         * an account is no more sensitive than [selfServiceAcrFloor]'s own reasoning already
         * covers, so this is exactly that floor, not a second definition of it.
         */
        fun requiredAcr(account: AccountProfile?): AcrLevel = selfServiceAcrFloor(account)
    }
}
