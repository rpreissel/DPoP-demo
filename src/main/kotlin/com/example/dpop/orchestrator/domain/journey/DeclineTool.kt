package com.example.dpop.orchestrator.domain.journey

import com.example.dpop.orchestrator.domain.journey.state.OfferingState
import com.example.dpop.tool_spi.ToolId

/**
 * The answer every offering state gives to "the user backed out of this tool": narrow the offer,
 * and if nothing offerable is left, hand over to the caller's own fallback.
 *
 * One function because the QUESTION is one, not because the code was repeated. Each strategy used
 * to answer it itself, and the answers had already drifted: six call sites asked
 * `(offered - declined).isEmpty()`, three asked [OfferingState.exhausted]. Only the latter also
 * accounts for availability, so the former could not see a last remaining offer that an admin
 * switch had disabled - narrowing into a state that renders an empty selection and sends the user
 * back to it, instead of down the fallback chain that was meant to catch exactly this
 * ([OfferingState.exhausted]'s own doc says every caller should fall back "through the same chain
 * as a decline would").
 *
 * [whenExhausted] stays the caller's: falling back is genuinely per-intent (plain `Cancel`, a
 * re-identification offer, an enrollment cascade), and it receives the full [declined] set because
 * some callers carry it into that fallback.
 */
inline fun declineTool(
    state: OfferingState,
    tool: ToolId,
    ctx: JourneyContext,
    whenExhausted: (declined: Set<ToolId>) -> Transition
): Transition {
    val narrowed = state.declining(tool)
    return if (narrowed.exhausted(ctx.availableTools)) whenExhausted(narrowed.declined)
    else Transition.To(narrowed)
}

/**
 * The two answers an [com.example.dpop.orchestrator.domain.journey.state.AnswerableState] prompt accepts,
 * as the client sends them ([JourneyEvent.Answered]). Defined once next to the event that carries
 * them rather than as a private constant in each strategy that uses them -
 * they are a wire contract, and four copies can drift apart while every prompt still looks fine.
 */
const val ANSWER_ACCEPT = "accept"
const val ANSWER_DECLINE = "decline"
