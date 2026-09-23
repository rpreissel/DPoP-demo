package com.example.dpop.orchestrator.journey

import com.example.dpop.orchestrator.kernel.ChannelType
import com.example.dpop.orchestrator.journey.state.AnswerableState
import com.example.dpop.orchestrator.journey.state.JourneyState
import com.example.dpop.orchestrator.journey.state.OfferingState
import com.example.dpop.orchestrator.session.ChannelSession
import com.example.dpop.orchestrator.tool.ToolAvailabilityService
import com.example.dpop.orchestrator.tool.ToolHandlerRegistry
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
 * [demo] rides ALONGSIDE [stepData], not inside it. It used to be stuffed in under a reserved key
 * and lifted back out by `ToolControllerSupport` - which only worked while stepData was an untyped
 * map, and was always a misuse: the demo block is explicitly not part of the step's contract
 * (tool_spi/Demo.kt). Now the two are simply two fields.
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
        return if (activatable.size == 1) {
            val toolId = activatable.single()
            Next.tool(toolId.value, toolRegistry.descriptorOf(toolId).startStep)
        } else {
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
        val stepData: StepData? = when {
            // Sorted here, where the list leaves for the client - not in the state's stored offer,
            // which is frozen for the journey's lifetime: a changed order applies to a running
            // journey's very next screen too.
            state is OfferingState && options.size > 1 -> SelectMethodStep(
                options = toolAvailabilityService.ordered(channelTypeOf(channel), options).map { it.value },
                title = state.selectionTitle,
                description = state.selectionDescription
            )
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

    private fun channelTypeOf(channel: ChannelSession): ChannelType =
        checkNotNull(channel.channel) { "Channel ${channel.channelSessionId} has no channel type" }
}
