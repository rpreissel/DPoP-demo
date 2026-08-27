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
    val catalog: ToolHandlerRegistry
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

    data class SubJourneyFinished(val achievedAcr: String?) : JourneyEvent

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

    /** No way forward at all. Ends the journey with 410 - never a mere "no candidates left". */
    data class Abort(val reason: String) : Decision
}

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
