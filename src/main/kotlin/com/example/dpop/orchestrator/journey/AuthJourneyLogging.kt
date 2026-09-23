package com.example.dpop.orchestrator.journey

import com.example.dpop.orchestrator.journeylog.LoggedJourney

/** What the journey log records about this journey - the journey side of `ChannelSession.forLog()`. */
fun AuthJourney.forLog(): LoggedJourney = LoggedJourney(
    journeyId = checkNotNull(journeyId) { "Journey without an id cannot be logged" },
    parentJourneyId = parentJourneyId,
    intent = checkNotNull(intent) { "Journey without an intent cannot be logged" }
)
