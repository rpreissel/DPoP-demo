package com.example.dpop.orchestrator.journey.strategy

import com.example.dpop.orchestrator.domain.AuthIntent
import com.example.dpop.orchestrator.domain.journey.IntentStrategy
import com.example.dpop.orchestrator.domain.journey.JourneyContext
import com.example.dpop.orchestrator.domain.journey.JourneyEvent
import com.example.dpop.orchestrator.domain.journey.Transition
import com.example.dpop.orchestrator.domain.journey.state.LogoutState
import org.springframework.stereotype.Component

/**
 * Logout as a journey: a single confirmation prompt, then an unconditional logout on accept.
 * No tools, no gates, no sub-journeys — the simplest possible intent.
 */
@Component
class LogoutStrategy : IntentStrategy<LogoutState> {

    override val intent = AuthIntent.LOGOUT

    override fun initialState(ctx: JourneyContext): LogoutState = LogoutState.ConfirmPending

    override fun transition(state: LogoutState, event: JourneyEvent, ctx: JourneyContext): Transition =
        when (state) {
            is LogoutState.ConfirmPending -> when (event) {
                is JourneyEvent.Answered -> when (event.answer) {
                    "accept" -> Transition.Logout
                    "decline" -> Transition.Cancel
                    else -> error("ConfirmPending does not understand answer '${event.answer}'")
                }
                else -> Transition.To(state)
            }
        }

}
