package com.example.dpop.orchestrator.journeylog

import com.example.dpop.orchestrator.kernel.AuthIntent
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

data class JourneyLogEntryView(
    val channelSessionId: UUID,
    /** APP or KEYCLOAK - makes the originating facade visible in the log UI. */
    val channelType: String?,
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

/**
 * Exactly what the log records about the channel an entry belongs to.
 *
 * A value, not the `ChannelSession` entity: the log is a trace, and a trace must not reach back
 * into the machine it traces. Taking the entity made `journeylog` import `session`, which
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
class JourneyLogService(
    private val journeyLogRepository: JourneyLogRepository
) {

    /** [journeyState] is a first-class field, like [eventType] - not just another entry in [detail]. */
    fun record(
        channel: LoggedChannel,
        journey: LoggedJourney,
        eventType: String,
        journeyState: String? = null,
        detail: Map<String, Any?> = emptyMap()
    ) {
        journeyLogRepository.save(
            JourneyLogEntry(
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
        journeyLogRepository.save(
            JourneyLogEntry(
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

    fun getLogFor(bindingKeyRef: String): JourneyLogResponse =
        JourneyLogResponse(journeyLogRepository.findByBindingKeyRefOrderByCreatedAtDesc(bindingKeyRef).map { it.toView() })

    /**
     * Every journey step ever recorded under [accountId], across every channel it was ever bound
     * to (APP or KEYCLOAK alike) - the account-scoped counterpart of [getLogFor], which only ever
     * sees APP channels (KEYCLOAK ones have no bindingKeyRef, docs/02-domaenenmodell.md Abschnitt 1).
     * Callers must already have proven they ARE this account (a channel bound to it) - this method
     * itself does no authorization, same contract as [getLogFor] trusting its own bindingKeyRef.
     *
     * [channelSessionIds] are resolved by the CALLER (which already holds the channel it
     * authorized against) rather than looked up here - the log does not read the session tables,
     * see [LoggedChannel]. They are passed in rather than filtering
     * [JourneyLogEntry.accountId] directly: a channel only gets its account bound partway through
     * (e.g. after ident-fsc/lookup-login completes), so entries logged earlier in that SAME journey
     * (its own "Started") never have that field set - filtering on it would silently truncate every
     * journey to "from binding onward" instead of showing it whole, which is exactly the
     * requirement here.
     */
    fun getLogForAccount(accountId: Long, channelSessionIds: List<UUID>): JourneyLogResponse {
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
