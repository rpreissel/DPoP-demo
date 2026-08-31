package com.example.dpop.orchestrator.journey

import com.example.dpop.account.AccountProfile
import com.example.dpop.orchestrator.journey.state.JourneyState
import com.example.dpop.orchestrator.policy.AuthEvidence
import com.example.dpop.orchestrator.policy.AuthPolicy
import com.example.dpop.orchestrator.session.ChannelState
import com.example.dpop.orchestrator.tool.ToolHandlerRegistry
import com.example.dpop.tool_spi.ToolDescriptor
import com.example.dpop.tool_spi.ToolOutcome

/**
 * The SPI each intent implements - symmetric to `tool_spi`, where tools describe themselves.
 *
 * A strategy DECIDES, it never ACTS: it gets a read-only [JourneyContext] and names an [Effect]
 * for JourneyService to execute. Everything with a side effect - creating accounts, recording
 * evidence, writing device links, capping ACR - is executed centrally by [JourneyService] and is
 * not reachable from here.
 */
interface IntentStrategy<S : JourneyState> {
    /** Which [AuthIntent] this strategy implements - one bean per entry in that enum. */
    val intent: AuthIntent

    /** Where a fresh journey of this intent begins, before any event has been seen. */
    fun initialState(ctx: JourneyContext): S

    /**
     * Where this intent begins when entered as another journey's precondition
     * ([Decision.RequireSubJourney]) instead of directly. Needs [targetAcr] because a
     * sub-journey's goal is set by whoever demanded it - a directly entered journey gets its goal
     * from [JourneyContext] via [initialState] instead.
     *
     * The default is a runtime error, not a compile error: nothing here can check statically that
     * only intents actually named in some [Decision.RequireSubJourney] override this.
     */
    fun initialStateForSubJourneyAcr(targetAcr: String, startingAcr: String): S =
        error("$intent cannot be entered as a sub-journey")

    /**
     * What a completed tool MEANS here - a pure value, no execution. The same successful
     * `ident-fsc` is "find or create the account" under FAST and "confirm the known account, else
     * 409" under STEP_UP/MANAGE. JourneyService executes the returned [Effect] and refreshes
     * [JourneyContext] (evidence, ACR) BEFORE calling [decide] with the resulting
     * `JourneyEvent.Completed` - that ordering is why this stays a separate method from [decide]
     * instead of folding into it: [decide]'s own logic (e.g. "is the floor satisfied now?") needs
     * the POST-effect context, which only exists once this has already run.
     */
    fun interpret(state: S, tool: ToolDescriptor, outcome: ToolOutcome.Completed): Effect

    /**
     * The one and only transition. "First offer", "after a completed tool", "after an abandoned
     * tool" and "back from a sub-journey" all answer the same question, so they are one method
     * with an event parameter instead of four.
     */
    fun decide(state: S, event: JourneyEvent, ctx: JourneyContext): Decision

    /** Which channel state a cancelled journey of this intent falls back to. */
    fun cancelledTo(state: S): ChannelState
}

/**
 * Everything a strategy may look at. Read-only by construction: [policy] and [catalog] answer
 * questions, they change nothing.
 */
data class JourneyContext(
    /** The account this journey concerns, once resolved - `null` before any identification/lookup. */
    val account: AccountProfile?,
    /** What this channel's session has already proven. */
    val evidence: AuthEvidence,
    /** The channel's durable lower bound - never a single run's target (that lives in the state). */
    val acrFloor: String,
    /** The calling device's DPoP-proven key thumbprint. */
    val bindingKeyRef: String,
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

    /** A tool finished successfully; [outcome] is what [IntentStrategy.interpret] turns into an [Effect]. */
    data class Completed(val tool: ToolDescriptor, val outcome: ToolOutcome.Completed) : JourneyEvent

    /** "Back"/"Switch": the user abandoned an activated tool without finishing it. */
    data class Abandoned(val tool: ToolDescriptor) : JourneyEvent

    /**
     * [intent] names WHICH sub-journey just finished - a resumed parent must never assume this by
     * construction ("only one caller today"), because a future second [Decision.RequireSubJourney]
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
     * than two choices - the owning intent's own [IntentStrategy.decide] alone decides which
     * values are valid.
     */
    data class Answered(val answer: String) : JourneyEvent
}

/**
 * What should happen next. Deliberately NOT a `Next` - the skip-if-single-candidate rule and the
 * routing derivation live once in [JourneyService], not once per intent.
 *
 * There is no separate "offer these tools" variant: a state already carries what it offers
 * ([JourneyState.activatable]), so [Advance] to that state IS the offer.
 */
sealed interface Decision {
    /** Move the journey to [to]; what it now offers is read straight off that state. */
    data class Advance(val to: JourneyState) : Decision

    /** Run another intent first, then resume this journey at [resumeWith]. */
    data class RequireSubJourney(
        val intent: AuthIntent,
        val targetAcr: String,
        val resumeWith: JourneyState
    ) : Decision

    /** Goal reached: consume the journey, the channel becomes AUTHENTICATED. */
    data object Authenticated : Decision

    /**
     * The user gave up (abandoned the last thing this journey could offer). Distinct from
     * [Abort]: nothing went wrong, so this ends like an explicit cancel - via
     * [IntentStrategy.cancelledTo], with a fresh start offered afterwards - rather than as a 410.
     */
    data object Cancel : Decision

    /**
     * Run [effect], then continue as [then] - deactivating a method, linking a device, or
     * deleting an account, each of which continues the journey afterward with [then]
     * (ordinarily [Authenticated] or [Logout]).
     */
    data class Execute(val effect: Effect, val then: Decision = Authenticated) : Decision

    /**
     * Confirmed logout: ends the channel for good. The journey is consumed, authContext
     * discarded, channel becomes LOGGED_OUT. Also the natural [Execute.then] for
     * [Effect.DeleteAccount].
     */
    data object Logout : Decision

    /** No way forward at all. Ends the journey with 410 - never a mere "no candidates left". */
    data class Abort(val reason: String) : Decision
}

/**
 * A named side effect a strategy decided, for [JourneyService] to actually execute - the strategy
 * never acts itself (see [IntentStrategy]'s own class doc). Two origins share this one
 * vocabulary: the first four variants answer "what did a just-completed tool establish" (returned
 * by [IntentStrategy.interpret]); the rest are a strategy's own effects wrapped in
 * [Decision.Execute], continuing the journey with [Decision.Execute.then] afterward.
 */
sealed interface Effect {
    /** Find or create the account for the identified person and record the identification. */
    data object AdoptIdentity : Effect

    /** The identified person must match the already-known account, else 409. */
    data object ConfirmIdentity : Effect

    /** A new credential was enrolled; [bindDevice] says whether it should also link this device. */
    data class AdoptCredential(val bindDevice: Boolean) : Effect

    /**
     * [useOutcomeAccount] is what makes lookup-based login safe: only an intent that expects a
     * tool to resolve the account itself accepts `Authenticated.accountId`. Everywhere else the
     * account must already be known from channel or journey.
     */
    data class AcceptProof(val useOutcomeAccount: Boolean, val bindDevice: Boolean) : Effect

    /**
     * Deactivate a method instance. Not a tool run; the strategy decides it, the machine executes
     * it (rejecting self-lockout).
     */
    data class Remove(val methodInstanceId: String) : Effect

    /**
     * Link the current device to [accountId] - the effect an accepted device-binding offer asks
     * for (see [JourneyEvent.Answered]).
     */
    data class LinkDevice(val accountId: Long) : Effect

    /**
     * Delete the account and everything it owns. An irreversible effect, so [JourneyService]
     * independently re-checks [REQUIRED_ACR] against the CURRENT evidence right before executing
     * it, exactly like it independently re-checks self-lockout before [Remove].
     */
    data class DeleteAccount(val accountId: Long) : Effect {
        companion object {
            /**
             * The ACR JourneyService independently re-checks right before executing this effect.
             * Lives here rather than in `DeleteAccountStrategy` so the generic machine can
             * reference it without importing a concrete [IntentStrategy] implementation.
             */
            const val REQUIRED_ACR = "loa2"
        }
    }
}
