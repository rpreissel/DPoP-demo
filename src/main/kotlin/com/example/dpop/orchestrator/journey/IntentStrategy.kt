package com.example.dpop.orchestrator.journey

import com.example.dpop.account.AccountProfile
import com.example.dpop.orchestrator.journey.state.JourneyState
import com.example.dpop.orchestrator.policy.AuthEvidence
import com.example.dpop.orchestrator.policy.AuthPolicy
import com.example.dpop.orchestrator.policy.MethodEvidence
import com.example.dpop.orchestrator.session.ChannelSession
import com.example.dpop.orchestrator.session.ChannelState
import com.example.dpop.orchestrator.tool.ToolHandlerRegistry
import com.example.dpop.tool_spi.ToolDescriptor
import com.example.dpop.tool_spi.ToolOutcome

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
     * Where this intent begins when entered as another journey's precondition
     * ([Transition.RequireSubJourney]) instead of directly. Needs [targetAcr] because a
     * sub-journey's goal is set by whoever demanded it - a directly entered journey gets its goal
     * from [JourneyContext] via [initialState] instead.
     *
     * The default is a runtime error, not a compile error: nothing here can check statically that
     * only intents actually named in some [Transition.RequireSubJourney] override this.
     */
    fun initialStateForSubJourneyAcr(targetAcr: String, startingAcr: String): S =
        error("$intent cannot be entered as a sub-journey")

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
    val channel: ChannelSession.Channel,
    /** The account this journey concerns, once resolved - `null` before any identification/lookup. */
    val account: AccountProfile?,
    /** What this channel's session has already proven. */
    val evidence: AuthEvidence,
    /** The channel's durable lower bound - never a single run's target (that lives in the state). */
    val acrFloor: String,
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
    val availableTools: Set<String>
) {
    fun requireAccount(): AccountProfile =
        checkNotNull(account) { "Strategy asked for an account before one was resolved" }
}

/** What just happened to the journey. */
sealed interface JourneyEvent {
    /** The journey was just created and has to produce its first offer. */
    data object Started : JourneyEvent

    /**
     * Evidence was reported directly, outside any orchestrator tool outcome - e.g. Keycloak's own
     * native authenticators (docs/05-api.md Abschnitt 3, Mock-Keycloak), the only
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
    data class SubJourneyFinished(val intent: AuthIntent, val achievedAcr: String?) : JourneyEvent

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

    /** Run another intent first, then resume this journey at [resumeWith]. */
    data class RequireSubJourney(
        val intent: AuthIntent,
        val targetAcr: String,
        val resumeWith: JourneyState
    ) : Transition

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
    data class Abort(val reason: String) : Transition
}

/**
 * A named side effect a strategy decided, for [JourneyService] to actually execute - the strategy
 * never acts itself (see [IntentStrategy]'s own class doc). Two origins share this one
 * vocabulary: the first four variants answer "what did a just-completed tool establish" (carried
 * by a [Transition.Perform] returned in reaction to [JourneyEvent.Completed], with the very
 * [ToolDescriptor]/[ToolOutcome] that arrived with it); the rest are a strategy's own actions,
 * likewise wrapped in [Transition.Perform].
 */
sealed interface Action {
    /** Find or create the account for the identified person and record the identification. */
    data class AdoptIdentity(val tool: ToolDescriptor, val outcome: ToolOutcome.Completed.Identified) : Action

    /** The identified person must match the already-known account, else 409. */
    data class ConfirmIdentity(val tool: ToolDescriptor, val outcome: ToolOutcome.Completed.Identified) : Action

    /**
     * A new credential was enrolled; [bindDevice] says whether it should also link this device.
     */
    data class AdoptCredential(
        val tool: ToolDescriptor,
        val outcome: ToolOutcome.Completed.Enrolled,
        val bindDevice: Boolean
    ) : Action

    /**
     * [useOutcomeAccount] is what makes lookup-based login safe: only an intent that expects a
     * tool to resolve the account itself accepts `outcome.accountId`. Everywhere else the account
     * must already be known from channel or journey.
     */
    data class AcceptProof(
        val tool: ToolDescriptor,
        val outcome: ToolOutcome.Completed.Authenticated,
        val useOutcomeAccount: Boolean,
        val bindDevice: Boolean
    ) : Action

    /**
     * Prime a fresh channel's evidence from [methods] before this journey's own first decision -
     * the Anfangs-Übergang ([JourneyService.start]'s `seedAction`, docs/ideen/journey-strategie-
     * vereinheitlichung.md #3), never a strategy's own decision: no strategy ever sees this
     * action, it is applied mechanically before `initialState()` even runs.
     */
    data class ApplyRestoredEvidence(val source: String, val methods: List<MethodEvidence>) : Action

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
     * Deactivate a method instance. Not a tool run; the strategy decides it, the machine executes
     * it (rejecting self-lockout).
     */
    data class Remove(val methodInstanceId: String) : Action

    /**
     * Link the current device to [accountId] - the action an accepted device-binding offer asks
     * for (see [JourneyEvent.Answered]).
     */
    data class LinkDevice(val accountId: Long) : Action

    /**
     * Delete the account and everything it owns. An irreversible action, so [JourneyService]
     * independently re-checks [REQUIRED_ACR] against the CURRENT evidence right before executing
     * it, exactly like it independently re-checks self-lockout before [Remove].
     */
    data class DeleteAccount(val accountId: Long) : Action {
        companion object {
            /**
             * The ACR JourneyService independently re-checks right before executing this action.
             * Lives here rather than in `DeleteAccountStrategy` so the generic machine can
             * reference it without importing a concrete [IntentStrategy] implementation.
             */
            const val REQUIRED_ACR = "loa2"
        }
    }
}
