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
 * A strategy DECIDES, it never ACTS: it gets a read-only [JourneyContext] and returns values
 * ([Interpretation], [Decision]). Everything with a side effect - creating accounts, recording
 * evidence, writing device links, capping ACR - is executed centrally by [JourneyService] and is
 * not reachable from here.
 */
interface IntentStrategy<S : JourneyState> {
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
     * 409" under STEP_UP/MANAGE.
     */
    fun interpret(state: S, tool: ToolDescriptor, outcome: ToolOutcome.Completed): Interpretation

    /**
     * The one and only transition. "First offer", "after a completed tool", "after an abandoned
     * tool" and "back from a sub-journey" all answer the same question, so they are one method
     * with an event parameter instead of four.
     */
    fun next(state: S, event: JourneyEvent, ctx: JourneyContext): Decision

    /** Which channel state an abandoned journey of this intent falls back to. */
    fun onCancel(state: S): ChannelState
}

/**
 * Everything a strategy may look at. Read-only by construction: [policy] and [catalog] answer
 * questions, they change nothing.
 */
data class JourneyContext(
    val account: AccountProfile?,
    val evidence: AuthEvidence,
    /** The channel's durable lower bound - never a single run's target (that lives in the state). */
    val acrFloor: String,
    val bindingKeyRef: String,
    /** The account this device is durably linked to, if any - independent of this channel. */
    val linkedAccountId: Long?,
    /** True while this journey runs as another one's precondition (docs/04-orchestrierung.md #6). */
    val isSubJourney: Boolean,
    val policy: AuthPolicy,
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
     * than two choices - the owning intent's own `next` alone decides which values are valid.
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
    data class Advance(val to: JourneyState) : Decision

    /** Run another intent first, then resume this journey at [resumeWith]. */
    data class RequireSubJourney(
        val intent: AuthIntent,
        val targetAcr: String,
        val resumeWith: JourneyState
    ) : Decision

    /** Goal reached: consume the journey, the channel becomes AUTHENTICATED. */
    data object Finish : Decision

    /**
     * The user gave up (abandoned the last thing this journey could offer). Distinct from
     * [Abort]: nothing went wrong, so this ends like an explicit cancel - via [onCancel], with a
     * fresh start offered afterwards - rather than as a 410.
     */
    data object Cancel : Decision

    /**
     * Deactivate a method instance and finish. An effect that is not a tool run; the strategy
     * decides it, the machine executes it (rejecting self-lockout).
     */
    data class Remove(val methodInstanceId: String) : Decision

    /**
     * Link the current device to [accountId], then finish - the effect an accepted device-binding
     * offer asks for (see [JourneyEvent.Answered]). Like [Remove], a non-tool effect the strategy
     * decides and the machine executes.
     */
    data class FinishWithDeviceLink(val accountId: Long) : Decision

    /**
     * Delete the account and everything it owns, then end the channel like a logout - the effect
     * DELETE_ACCOUNT asks for once any factor was freshly re-proven. Like [Remove]/
     * [FinishWithDeviceLink], a non-tool effect the strategy decides and the machine executes -
     * but unlike those two, an irreversible one, so [JourneyService] does not just trust that the
     * strategy's own state machine reached this decision correctly: it independently re-checks
     * [DELETE_ACCOUNT_REQUIRED_ACR] against the CURRENT [JourneyContext.evidence] right before
     * executing it, exactly like [JourneyService.removeMethod] independently re-checks
     * self-lockout before [Remove]. Deliberately carries no `requiredAcr` of its own: a value
     * handed in BY the decision that produced it is the strategy grading its own homework - a
     * buggy strategy could smuggle in too-low a bar and the "independent" check would
     * rubber-stamp it. JourneyService checks against the fixed constant below instead.
     */
    data class DeleteAccount(val accountId: Long) : Decision

    /** No way forward at all. Ends the journey with 410 - never a mere "no candidates left". */
    data class Abort(val reason: String) : Decision
}

/**
 * The ACR account deletion demands before JourneyService will actually execute
 * [Decision.DeleteAccount] - lives here, in the generic journey package, rather than inside
 * `DeleteAccountStrategy` (`journey.strategy`), specifically so JourneyService's independent
 * re-check can reference it without importing a concrete [IntentStrategy] implementation: the
 * machine is deliberately generic over every intent (`strategiesByIntent: Map<AuthIntent,
 * IntentStrategy<*>>`), and depending on one specific strategy class by name would break that.
 * `DeleteAccountStrategy` itself imports this same constant for its own gate, so there is still
 * exactly one source of truth - the dependency just runs in the correct direction, generic layer
 * to strategy, never the reverse.
 */
const val DELETE_ACCOUNT_REQUIRED_ACR = "loa2"

/**
 * The meaning of a completed tool, as a value. The mechanical parts (which enrollment ref, which
 * amr, which achievedAcr) are read from the outcome by the machine - only the parts that DIFFER
 * per intent appear here.
 */
sealed interface Interpretation {
    /** Find or create the account for the identified person and record the identification. */
    data object AdoptIdentity : Interpretation

    /** The identified person must match the already-known account, else 409. */
    data object ConfirmIdentity : Interpretation

    data class AdoptCredential(val bindDevice: Boolean) : Interpretation

    /**
     * [useOutcomeAccount] is what makes lookup-based login safe: only an intent that expects a
     * tool to resolve the account itself accepts `Authenticated.accountId`. Everywhere else the
     * account must already be known from channel or journey.
     */
    data class AcceptProof(val useOutcomeAccount: Boolean, val bindDevice: Boolean) : Interpretation
}
