package com.example.dpop.orchestrator.journey

import com.example.dpop.orchestrator.kernel.ChannelType
import com.example.dpop.orchestrator.journey.state.AnswerableState
import com.example.dpop.orchestrator.journey.state.JourneyState
import com.example.dpop.orchestrator.journey.state.OfferingState
import com.example.dpop.orchestrator.session.ChannelSession
import com.example.dpop.orchestrator.tool.ToolAvailabilityService
import com.example.dpop.orchestrator.tool.ToolHandlerRegistry
import com.example.dpop.texts.Text
import com.example.dpop.tool_spi.StepData
import com.example.dpop.tool_api.Next
import com.example.dpop.tool_spi.ToolId
import org.springframework.stereotype.Component

/**
 * `next` plus whatever the step needs to render - the pair every caller wants back. `next` is
 * null only for a decision that ends the channel for good ([Transition.Logout]) -
 * ChannelService.respond() derives the real next itself in every other case.
 */
/**
 * One step as the client will see it.
 *
 * [demo] rides ALONGSIDE [stepData], not inside it under a reserved key: the demo block is
 * explicitly not part of the step's contract (tool_spi/Demo.kt), and a typed stepData has no
 * room for it anyway. So the two are simply two fields.
 */
data class Step(
    val next: Next?,
    val stepData: StepData? = null,
    val demo: Map<String, Any?>? = null
)

/**
 * The routing phase of a journey step: turning a [JourneyState] into the `next` the client is
 * sent to, plus the data that step needs to render. Split out of [JourneyService] because this is
 * the one phase that is a pure function of (state, available tools) - it reads no journey, writes
 * nothing, and has no say in what a transition MEANS.
 *
 * Keeping it in one small class is what makes "next is a pure function of the state"
 * (docs/04-orchestrierung.md #4) checkable by looking at a single file rather than by trusting
 * that no other branch of a 1000-line service quietly derives a route of its own.
 */
@Component
class JourneyRouting(
    private val toolRegistry: ToolHandlerRegistry,
    private val toolAvailabilityService: ToolAvailabilityService
) {
    /**
     * What the client declared it can render, minus what the operator switched off for this
     * channel's type. Live, never cached: a backend disable must take effect on the very next step
     * of an already-running journey.
     */
    fun availableToolsOf(channel: ChannelSession): Set<ToolId> =
        (channel.availableClientTools - toolAvailabilityService.disabledToolIds(channelTypeOf(channel)))
            .mapTo(mutableSetOf()) { ToolId(it) }

    /**
     * `next` as a pure function of the state (docs/04-orchestrierung.md #4). The same
     * [JourneyState.activatable] that answers "may this tool be activated" also decides where the
     * client goes - one function, so the two can never disagree.
     */
    fun nextFor(state: JourneyState, availableTools: Set<ToolId>): Next {
        state.active?.let { return Next.tool(it.toolId.value, it.step, it.toolSessionId) }
        val activatable = state.activatable(availableTools)
        val single = activatable.singleOrNull()
        return if (single != null && completesOnActivation(single) == null) {
            Next.tool(single.value, toolRegistry.descriptorOf(single).startStep)
        } else {
            // A single candidate that completes on activation opens the selection page too: started
            // on its own it would change the account before the user saw anything (ToolDescriptor).
            // Several candidates open a selection page; zero means an orchestrator-owned page
            // that isn't a choice at all (a confirmation, the finished screen), or a state whose
            // only offer just became unavailable - the empty option list resolves itself once the
            // client's next action (abandon/activate) drives an actual transition.
            Next.orchestrator(state.selectionContext, state.selectionStep)
        }
    }

    /**
     * The complete current step, including selection options and prompts.
     *
     * The three cases are mutually exclusive by construction, which is why [StepData] can be a
     * union rather than one object with everything optional: a state either offers a choice, or
     * auto-activates its single candidate, or waits for an answer.
     */
    fun stepFor(state: JourneyState, channel: ChannelSession): Step {
        val availableTools = availableToolsOf(channel)
        val options = state.activatable(availableTools)
        val completesAtOnce = options.singleOrNull()?.let { completesOnActivation(it) }
        val stepData: StepData? = when {
            // Sorted here, where the list leaves for the client - not in the state's stored offer,
            // which is frozen for the journey's lifetime: a changed order applies to a running
            // journey's very next screen too.
            state is OfferingState && options.size > 1 -> selectMethodStep(state, channel, options)
            // The only candidate completes on activation: offered like a choice of one, and the
            // description says why there is just this one and what choosing it does.
            state is OfferingState && completesAtOnce != null -> selectMethodStep(state, channel, options)
            // Single-option auto-activate: the selection screen is skipped, so pass the
            // description as a contextual message so the tool form can explain WHY this
            // step is required (e.g. "E-Mail-Bestätigung ausstehend" during fast-access).
            state is OfferingState && options.size == 1 ->
                state.selectionDescription?.let { MessageStep(it) }
            state is AnswerableState -> ConfirmStep(state.prompt)
            else -> null
        }
        return Step(nextFor(state, availableTools), stepData)
    }

    /**
     * The selection page of [state] even when only one candidate is left - for "Zurück"
     * (JourneyService.back): whoever goes back wants to choose, not to land in the same tool
     * again at once, which [nextFor]'s single-candidate auto-start would do.
     */
    fun selectionFor(state: OfferingState, channel: ChannelSession): Step {
        val options = state.activatable(availableToolsOf(channel))
        return Step(Next.orchestrator(state.selectionContext, state.selectionStep), selectMethodStep(state, channel, options))
    }

    private fun selectMethodStep(state: OfferingState, channel: ChannelSession, options: Set<ToolId>): SelectMethodStep {
        val completesAtOnce = options.singleOrNull()?.let { completesOnActivation(it) }
        return SelectMethodStep(
            options = toolAvailabilityService.ordered(channelTypeOf(channel), options).map { it.value },
            title = state.selectionTitle,
            description = completesAtOnce?.let { Text("Nur dieses Verfahren steht hier noch zur Wahl. {grund}", "grund" to it) }
                ?: state.selectionDescription
        )
    }

    private fun completesOnActivation(toolId: ToolId): Text? = toolRegistry.descriptorOf(toolId).completesOnActivation

    private fun channelTypeOf(channel: ChannelSession): ChannelType =
        checkNotNull(channel.channel) { "Channel ${channel.channelSessionId} has no channel type" }
}
