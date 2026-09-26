package com.example.dpop.orchestrator.domain.journey

import com.example.dpop.orchestrator.journey.JourneyService
import com.example.dpop.tool_spi.AcrLevel
import com.example.dpop.tool_spi.ToolDescriptor
import com.example.dpop.tool_spi.ToolOutcome
import com.example.dpop.orchestrator.domain.AuthIntent

/** What just happened to the journey. */
sealed interface JourneyEvent {
    /** The journey was just created and has to produce its first offer. */
    data object Started : JourneyEvent

    /**
     * Evidence was reported directly, outside any orchestrator tool outcome - e.g. Keycloak's own
     * native authenticators (docs/05-api.md Abschnitt 3), the only
     * source today. The channel's [com.example.dpop.orchestrator.session.EvidenceTrail] was already
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
