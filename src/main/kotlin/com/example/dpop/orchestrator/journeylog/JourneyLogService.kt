package com.example.dpop.orchestrator.journeylog

import com.example.dpop.orchestrator.journey.AuthJourney
import com.example.dpop.orchestrator.session.ChannelSession
import com.example.dpop.orchestrator.session.ChannelSessionRepository
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

data class JourneyLogEntryView(
    val channelSessionId: UUID,
    /** Null until the channel resolves an account - see [JourneyLogEntry.accountId]. */
    val accountId: Long?,
    /** Null for a channel-level event with no journey of its own (see [JourneyLogService.recordForChannel]). */
    val journeyId: UUID?,
    /** Set when [journeyId] ran as another journey's precondition - lets the UI nest it under that parent instead of showing it as an unrelated journey. */
    val parentJourneyId: UUID?,
    val intent: String?,
    val eventType: String,
    /** The JourneyState subtype the journey was in when this event happened (e.g. "AwaitingTan") - null for a channel-level event. */
    val journeyState: String?,
    val detail: Map<String, Any?>,
    val createdAt: Instant
)

data class JourneyLogResponse(val entries: List<JourneyLogEntryView>)

@Service
class JourneyLogService(
    private val journeyLogRepository: JourneyLogRepository,
    private val channelSessionRepository: ChannelSessionRepository
) {

    /** [channel]/[journey] are always in scope at the call sites in JourneyService - no extra lookups needed. [journeyState] is a first-class field, like [eventType] - not just another entry in [detail]. */
    fun record(
        channel: ChannelSession,
        journey: AuthJourney,
        eventType: String,
        journeyState: String? = null,
        detail: Map<String, Any?> = emptyMap()
    ) {
        journeyLogRepository.save(
            JourneyLogEntry(
                bindingKeyRef = channel.bindingKeyRef,
                accountId = channel.accountId,
                channelSessionId = checkNotNull(channel.channelSessionId),
                journeyId = checkNotNull(journey.journeyId),
                parentJourneyId = journey.parentJourneyId,
                intent = checkNotNull(journey.intent),
                eventType = eventType,
                journeyState = journeyState,
                detail = detail
            )
        )
    }

    /** For an event that isn't part of any journey - e.g. logging out of an AUTHENTICATED channel with nothing currently running, which would otherwise leave no trace at all. */
    fun recordForChannel(channel: ChannelSession, eventType: String, detail: Map<String, Any?> = emptyMap()) {
        journeyLogRepository.save(
            JourneyLogEntry(
                bindingKeyRef = channel.bindingKeyRef,
                accountId = channel.accountId,
                channelSessionId = checkNotNull(channel.channelSessionId),
                journeyId = null,
                parentJourneyId = null,
                intent = null,
                eventType = eventType,
                journeyState = null,
                detail = detail
            )
        )
    }

    fun getLogFor(bindingKeyRef: String): JourneyLogResponse =
        JourneyLogResponse(journeyLogRepository.findByBindingKeyRefOrderByCreatedAtDesc(bindingKeyRef).map { it.toView() })

    /**
     * Every journey step ever recorded under [accountId], across every channel it was ever bound
     * to (APP or KEYCLOAK alike) - the account-scoped counterpart of [getLogFor], which only ever
     * sees APP channels (KEYCLOAK ones have no bindingKeyRef, docs/ideen/web-keycloak-kanal.md #5).
     * Callers must already have proven they ARE this account (a channel bound to it) - this method
     * itself does no authorization, same contract as [getLogFor] trusting its own bindingKeyRef.
     *
     * Resolved via [ChannelSessionRepository.findByAccountId] first, not by filtering
     * [JourneyLogEntry.accountId] directly: a channel only gets its account bound partway through
     * (e.g. after ident-fsc/lookup-login completes), so entries logged earlier in that SAME journey
     * (its own "Started") never have that field set - filtering on it would silently truncate every
     * journey to "from binding onward" instead of showing it whole, which is exactly the
     * requirement here.
     */
    fun getLogForAccount(accountId: Long): JourneyLogResponse {
        val channelSessionIds = channelSessionRepository.findByAccountId(accountId).mapNotNull { it.channelSessionId }
        val channelEntries = if (channelSessionIds.isEmpty()) {
            emptyList()
        } else {
            journeyLogRepository.findByChannelSessionIdInOrderByCreatedAtDesc(channelSessionIds)
        }
        val entries = (channelEntries + journeyLogRepository.findByAccountIdOrderByCreatedAtDesc(accountId))
            .distinctBy { it.logId }
            .sortedByDescending { it.createdAt }
        return JourneyLogResponse(entries.map { it.toView() })
    }

    private fun JourneyLogEntry.toView() = JourneyLogEntryView(
        channelSessionId = checkNotNull(channelSessionId),
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
