package com.example.dpop.orchestrator.journey.strategy

import com.example.dpop.orchestrator.journey.AuthIntent
import com.example.dpop.orchestrator.journey.Decision
import com.example.dpop.orchestrator.journey.Effect
import com.example.dpop.orchestrator.journey.IntentStrategy
import com.example.dpop.orchestrator.journey.JourneyContext
import com.example.dpop.orchestrator.journey.JourneyEvent
import com.example.dpop.orchestrator.journey.state.LogoutState
import com.example.dpop.orchestrator.session.ChannelState
import com.example.dpop.tool_spi.ToolDescriptor
import com.example.dpop.tool_spi.ToolOutcome
import org.springframework.stereotype.Component

/**
 * Logout as a journey: a single confirmation prompt, then an unconditional logout on accept.
 * No tools, no gates, no sub-journeys — the simplest possible intent.
 */
@Component
class LogoutStrategy : IntentStrategy<LogoutState> {

    override val intent = AuthIntent.LOGOUT

    override fun initialState(ctx: JourneyContext): LogoutState = LogoutState.ConfirmPending

    override fun interpret(state: LogoutState, tool: ToolDescriptor, outcome: ToolOutcome.Completed): Effect =
        error("LOGOUT never offers tools")

    override fun decide(state: LogoutState, event: JourneyEvent, ctx: JourneyContext): Decision =
        when (state) {
            is LogoutState.ConfirmPending -> when (event) {
                is JourneyEvent.Answered -> when (event.answer) {
                    "accept" -> Decision.Logout
                    "decline" -> Decision.Cancel
                    else -> error("ConfirmPending does not understand answer '${event.answer}'")
                }
                else -> Decision.Advance(state)
            }
        }

    override fun cancelledTo(state: LogoutState): ChannelState = ChannelState.AUTHENTICATED
}
