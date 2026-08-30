package com.example.dpop.orchestrator.journeylog

import com.example.dpop.orchestrator.journey.AuthJourney
import com.example.dpop.orchestrator.session.ChannelSession
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

data class JourneyLogEntryView(
    val channelSessionId: UUID,
    val journeyId: UUID,
    /** Set when [journeyId] ran as another journey's precondition - lets the UI nest it under that parent instead of showing it as an unrelated journey. */
    val parentJourneyId: UUID?,
    val intent: String,
    val eventType: String,
    val detail: Map<String, Any?>,
    val createdAt: Instant
)

data class JourneyLogResponse(val entries: List<JourneyLogEntryView>)

@Service
class JourneyLogService(private val journeyLogRepository: JourneyLogRepository) {

    /** [channel]/[journey] are always in scope at the call sites in JourneyService - no extra lookups needed. */
    fun record(channel: ChannelSession, journey: AuthJourney, eventType: String, detail: Map<String, Any?> = emptyMap()) {
        journeyLogRepository.save(
            JourneyLogEntry(
                bindingKeyRef = checkNotNull(channel.bindingKeyRef) { "Channel without a binding key" },
                channelSessionId = checkNotNull(channel.channelSessionId),
                journeyId = checkNotNull(journey.journeyId),
                parentJourneyId = journey.parentJourneyId,
                intent = checkNotNull(journey.intent),
                eventType = eventType,
                detail = detail
            )
        )
    }

    fun getLogFor(bindingKeyRef: String): JourneyLogResponse =
        JourneyLogResponse(
            journeyLogRepository.findByBindingKeyRefOrderByCreatedAtDesc(bindingKeyRef).map {
                JourneyLogEntryView(
                    channelSessionId = checkNotNull(it.channelSessionId),
                    journeyId = checkNotNull(it.journeyId),
                    parentJourneyId = it.parentJourneyId,
                    intent = checkNotNull(it.intent).name,
                    eventType = checkNotNull(it.eventType),
                    detail = it.detail.orEmpty(),
                    createdAt = checkNotNull(it.createdAt)
                )
            }
        )
}
