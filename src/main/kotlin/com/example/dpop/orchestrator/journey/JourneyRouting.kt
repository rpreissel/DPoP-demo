package com.example.dpop.orchestrator.journey

import com.example.dpop.orchestrator.journey.state.AnswerableState
import com.example.dpop.orchestrator.journey.state.JourneyState
import com.example.dpop.orchestrator.journey.state.OfferingState
import com.example.dpop.orchestrator.session.ChannelSession
import com.example.dpop.orchestrator.tool.ToolAvailabilityService
import com.example.dpop.orchestrator.tool.ToolHandlerRegistry
import com.example.dpop.tool_api.Next
import com.example.dpop.tool_spi.ToolId
import org.springframework.stereotype.Component

/**
 * `next` plus whatever the step needs to render - the pair every caller wants back. `next` is
 * null only for a decision that ends the channel for good ([Transition.Logout]) -
 * ChannelService.respond() derives the real next itself in every other case.
 */
data class Step(val next: Next?, val stepData: Map<String, Any?>? = null)

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
    /** Live, never cached: a backend disable must take effect on the very next step of an already-running journey. */
    fun availableToolsOf(channel: ChannelSession): Set<ToolId> =
        (channel.availableClientTools - toolAvailabilityService.disabledToolIds()).mapTo(mutableSetOf()) { ToolId(it) }

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

    /** The complete current step, including selection options and prompts. */
    fun stepFor(state: JourneyState, availableTools: Set<ToolId>): Step {
        val options = state.activatable(availableTools)
        val stepData = buildMap<String, Any?> {
            if (state is OfferingState && options.size > 1) {
                put("options", options.map { it.value })
                put("title", state.selectionTitle)
                state.selectionDescription?.let { put("description", it) }
            }
            // Single-option auto-activate: the selection screen is skipped, so pass the
            // description as a contextual message so the tool form can explain WHY this
            // step is required (e.g. "E-Mail-Bestätigung ausstehend" during fast-access).
            if (state is OfferingState && options.size == 1) {
                state.selectionDescription?.let { put("message", it) }
            }
            if (state is AnswerableState) put("prompt", state.prompt)
        }
        return Step(nextFor(state, availableTools), stepData.ifEmpty { null })
    }
}
