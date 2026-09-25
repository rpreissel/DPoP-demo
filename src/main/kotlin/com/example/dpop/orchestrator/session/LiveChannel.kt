package com.example.dpop.orchestrator.session

import com.example.dpop.orchestrator.kernel.OrchestratorException
import com.example.dpop.texts.Text

/**
 * A channel that had not ended ([ChannelState.isTerminal]) when it was looked up - the only form in
 * which `JourneyService` lets anyone start, move or end a journey on it (docs/invarianten.md I-1,
 * review 2026-09 fahrplan Phase D step 19).
 *
 * Before this type, "an ended channel stays ended" was a check each entry point had to remember -
 * and two had not: a step-up on a logged-out channel started a journey and flipped it back to
 * STEP_UP_IN_PROGRESS, and reading an EXPIRED channel restarted its entry journey. The private
 * constructor makes that unrepresentable: there is no value to hand the journey machine for a dead
 * channel.
 *
 * Like `RunningJourney` it is a witness for the moment of the lookup, not a live view: the
 * transition it is handed to may end the channel (a confirmed logout).
 */
class LiveChannel private constructor(val session: ChannelSession) {

    companion object {
        fun of(session: ChannelSession): LiveChannel? =
            session.takeIf { it.state?.isTerminal != true }?.let(::LiveChannel)

        fun require(session: ChannelSession): LiveChannel =
            of(session) ?: throw OrchestratorException.invalidState(Text("This channel session has ended"), "state=${session.state}")
    }
}
