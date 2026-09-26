package com.example.dpop.orchestrator.journeytrace

import com.example.dpop.orchestrator.kernel.AuthIntent
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID
import io.swagger.v3.oas.annotations.media.Schema

data class JourneyTraceEntryView(
    val channelSessionId: UUID,
    /** APP or KEYCLOAK - makes the originating facade visible in the log UI. */
    val channelType: String?,
    /** Null until the channel resolves an account - see [JourneyTraceEntry.accountId]. */
    val accountId: Long?,
    /** Null for a channel-level event with no journey of its own (see [JourneyTraceService.recordForChannel]). */
    val journeyId: UUID?,
    /** Set when [journeyId] ran as another journey's precondition - lets the UI nest it under that parent instead of showing it as an unrelated journey. */
    val parentJourneyId: UUID?,
    val intent: String?,
    val eventType: String,
    /** The JourneyState subtype the journey was in when this event happened (e.g. "AwaitingTan") - null for a channel-level event. */
    val journeyState: String?,
    /**
     * Whatever the event had to say - strings, numbers, nested lists, depending on the event.
     *
     * `additionalProperties: true` rather than the default springdoc infers from `Map<String, Any?>`:
     * that produces `additionalProperties: {type: object}`, which says the VALUES are objects. They
     * are not - `mapOf("toolId" to ...)` puts a string in there. A generated client typed the map
     * as `{ [key: string]: object }`, which is both unhelpful and wrong.
     */
    @field:Schema(additionalProperties = Schema.AdditionalPropertiesValue.TRUE)
    val detail: Map<String, Any?>,
    val createdAt: Instant
)

data class JourneyTraceResponse(val entries: List<JourneyTraceEntryView>)

/**
 * Exactly what the log records about the channel an entry belongs to.
 *
 * A value, not the `ChannelSession` entity: the log is a trace, and a trace must not reach back
 * into the machine it traces. Taking the entity made `journeytrace` import `session`, which
 * `session` in turn imports for its own retention - a cycle created purely by convenience at the
 * call site. Each caller now says which four values it is logging, which is also the honest list
 * of what ends up in the table.
 */
data class LoggedChannel(
    val channelSessionId: UUID,
    val bindingKeyRef: String?,
    val channelType: String?,
    val accountId: Long?
)

/** The journey side of the same split - see [LoggedChannel]. */
data class LoggedJourney(
    val journeyId: UUID,
    val parentJourneyId: UUID?,
    val intent: AuthIntent
)

@Service
class JourneyTraceService(
    private val journeyTraceRepository: JourneyTraceRepository
) {

    /** [journeyState] is a first-class field, like [eventType] - not just another entry in [detail]. */
    fun record(
        channel: LoggedChannel,
        journey: LoggedJourney,
        eventType: String,
        journeyState: String? = null,
        detail: Map<String, Any?> = emptyMap()
    ) {
        journeyTraceRepository.save(
            JourneyTraceEntry(
                bindingKeyRef = channel.bindingKeyRef,
                channelType = channel.channelType,
                accountId = channel.accountId,
                channelSessionId = channel.channelSessionId,
                journeyId = journey.journeyId,
                parentJourneyId = journey.parentJourneyId,
                intent = journey.intent,
                eventType = eventType,
                journeyState = journeyState,
                detail = detail
            )
        )
    }

    /** For an event that isn't part of any journey - e.g. logging out of an AUTHENTICATED channel with nothing currently running, which would otherwise leave no trace at all. */
    fun recordForChannel(channel: LoggedChannel, eventType: String, detail: Map<String, Any?> = emptyMap()) {
        journeyTraceRepository.save(
            JourneyTraceEntry(
                bindingKeyRef = channel.bindingKeyRef,
                channelType = channel.channelType,
                accountId = channel.accountId,
                channelSessionId = channel.channelSessionId,
                journeyId = null,
                parentJourneyId = null,
                intent = null,
                eventType = eventType,
                journeyState = null,
                detail = detail
            )
        )
    }

    /**
     * The newest [limit] entries across every channel and account - the operator's view, with no
     * authorization of its own (its controller sits behind the admin login).
     *
     * An entry logged before its channel resolved an account carries no `accountId` (it is only
     * bound partway through, e.g. once ident-fsc completes); here it inherits the one a later entry of the SAME channel carries, so
     * a journey is attributed to its person as a whole rather than only from binding onward.
     */
    fun getRecent(limit: Int): JourneyTraceResponse {
        val entries = journeyTraceRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, limit))
        val accountByChannel = entries
            .filter { it.accountId != null }
            .associate { checkNotNull(it.channelSessionId) to checkNotNull(it.accountId) }
        return JourneyTraceResponse(entries.map { entry ->
            entry.toView().let { view -> view.copy(accountId = view.accountId ?: accountByChannel[view.channelSessionId]) }
        })
    }

    private fun JourneyTraceEntry.toView() = JourneyTraceEntryView(
        channelSessionId = checkNotNull(channelSessionId),
        channelType = channelType,
        accountId = accountId,
        journeyId = journeyId,
        parentJourneyId = parentJourneyId,
        intent = intent?.name,
        eventType = checkNotNull(eventType),
        journeyState = journeyState,
        detail = detail.orEmpty(),
        createdAt = checkNotNull(createdAt)
    )
}
