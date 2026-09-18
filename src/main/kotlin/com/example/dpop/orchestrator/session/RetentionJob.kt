package com.example.dpop.orchestrator.session

import com.example.dpop.orchestrator.journey.AuthJourneyRepository
import com.example.dpop.orchestrator.journeylog.JourneyLogRepository
import com.example.dpop.orchestrator.kc.KeycloakAdminClient
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant

/**
 * Retention for the orchestrator's own possession chain (docs/07-betrieb.md #3): cleaned from
 * the inside out (ToolSession -> AuthJourney -> ChannelSession+AuthContext+AuthEvidence) so a
 * row's FK target is already gone by the time it would be deleted. The age-based AuthJourney
 * sweep above only catches journeys old enough on their own clock, though, so [deleteChannels]
 * additionally clears any journeys still pointing at the channels it is about to delete (a
 * confirmed-dead KEYCLOAK channel can be much younger than the journey retention window).
 * SessionEvent is independent - it deliberately outlives the sessions it references (dangling
 * ids are expected, not a defect). The same holds for JourneyLogEntry and AttemptThrottle: both
 * are keyed by ids they do not constrain, so both are swept purely by age - and both MUST be
 * swept, because neither is bounded by anything else. account.*, AuthSmsEnrollment and
 * ext_stammdaten.person/id_fsc.code belong to the account, never touched here.
 */
@Component
class RetentionJob(
    private val toolSessionRepository: ToolSessionRepository,
    private val journeyRepository: AuthJourneyRepository,
    private val channelSessionRepository: ChannelSessionRepository,
    private val authContextRepository: AuthContextRepository,
    private val authEvidenceRepository: AuthEvidenceRepository,
    private val sessionEventRepository: SessionEventRepository,
    private val journeyLogRepository: JourneyLogRepository,
    private val attemptThrottleRepository: AttemptThrottleRepository,
    // Optional: only present under the `keycloak` profile (KeycloakAdminClient.kt's own doc) -
    // RetentionJob itself runs in every profile, so it must tolerate the bean being absent.
    private val keycloakAdminClient: ObjectProvider<KeycloakAdminClient>
) {
    private val log = LoggerFactory.getLogger(RetentionJob::class.java)

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 60_000)
    @Transactional
    fun cleanup() {
        val now = Instant.now()

        toolSessionRepository.deleteByExpiresAtBefore(now.minus(TOOL_SESSION_RETENTION))
        deleteExpiredJourneys(now.minus(JOURNEY_RETENTION))

        val confirmedDeadKcChannels = confirmedDeadKcChannels(now)
        deleteChannels(confirmedDeadKcChannels)

        deleteExpiredChannels(now.minus(CHANNEL_SESSION_RETENTION))

        sessionEventRepository.deleteByCreatedAtBefore(now.minus(SESSION_EVENT_RETENTION))

        val journeyLogEntries = journeyLogRepository.deleteByCreatedAtBefore(now.minus(JOURNEY_LOG_RETENTION))
        val staleCounters = attemptThrottleRepository.deleteStaleCounters(now.minus(ATTEMPT_THROTTLE_RETENTION), now)
        if (journeyLogEntries > 0 || staleCounters > 0) {
            log.info(
                "Retention: deleted {} journey log entry/entries and {} attempt throttle counter(s)",
                journeyLogEntries,
                staleCounters
            )
        }
    }

    /**
     * `KEYCLOAK` channels whose own (short, per-flow-run) TTL already passed AND whose durable
     * Keycloak session is affirmatively confirmed gone - safe to delete now instead of waiting out
     * [CHANNEL_SESSION_RETENTION] like every other channel (docs/07-betrieb.md
     * Abschnitt 3): Keycloak owns logout entirely and never tells the orchestrator
     * when it happens, so without this, a channel whose session already ended sits around for up
     * to [CHANNEL_SESSION_RETENTION] for no reason. A channel this can't affirmatively confirm
     * (no client configured, no durable session id recorded yet, or the Admin API call itself
     * failed) is deliberately left alone here - [KeycloakAdminClient.isSessionAlive]'s own doc on
     * why `null` must never be treated as "gone".
     */
    private fun confirmedDeadKcChannels(now: Instant): List<ChannelSession> {
        val client = keycloakAdminClient.getIfAvailable() ?: return emptyList()
        return channelSessionRepository.findByChannelAndExpiresAtBefore(ChannelSession.Channel.KEYCLOAK, now)
            .filter { channel ->
                val accountId = channel.accountId
                val sessionId = channel.durableKcSessionId
                accountId != null && sessionId != null && client.isSessionAlive(accountId, sessionId) == false
            }
    }

    /**
     * Pages through due journeys in fixed-size batches instead of loading the whole backlog's ids
     * (and building one unbounded `IN`-list) in a single go (B4) - deletes every returned batch
     * before asking again, so page 0 always reflects the current remaining backlog.
     */
    private fun deleteExpiredJourneys(cutoff: Instant) {
        var batch = journeyRepository.findIdsForRetention(cutoff, PageRequest.of(0, RETENTION_BATCH_SIZE))
        while (batch.isNotEmpty()) {
            toolSessionRepository.deleteByJourneyIdIn(batch)
            journeyRepository.deleteAllByIdInBatch(batch)
            batch = journeyRepository.findIdsForRetention(cutoff, PageRequest.of(0, RETENTION_BATCH_SIZE))
        }
    }

    /** Same batching as [deleteExpiredJourneys], for the channel-session side of retention (B4). */
    private fun deleteExpiredChannels(cutoff: Instant) {
        var batch = channelSessionRepository.findByExpiresAtBefore(cutoff, PageRequest.of(0, RETENTION_BATCH_SIZE))
        while (batch.isNotEmpty()) {
            deleteChannels(batch)
            batch = channelSessionRepository.findByExpiresAtBefore(cutoff, PageRequest.of(0, RETENTION_BATCH_SIZE))
        }
    }

    private fun deleteChannels(channels: List<ChannelSession>) {
        if (channels.isEmpty()) return
        val orphanedAuthContextIds = channels.mapNotNull { it.authContextId }
        val orphanedAuthEvidenceIds = channels.mapNotNull { it.authEvidenceId }
        val channelSessionIds = channels.mapNotNull { it.channelSessionId }
        if (channelSessionIds.isNotEmpty()) {
            val journeyIds = journeyRepository.findIdsByChannelSessionIdIn(channelSessionIds)
            if (journeyIds.isNotEmpty()) {
                toolSessionRepository.deleteByJourneyIdIn(journeyIds)
                journeyRepository.deleteAllByIdInBatch(journeyIds)
            }
        }
        channelSessionRepository.deleteAll(channels)
        if (orphanedAuthContextIds.isNotEmpty()) {
            authContextRepository.deleteAllById(orphanedAuthContextIds)
        }
        if (orphanedAuthEvidenceIds.isNotEmpty()) {
            authEvidenceRepository.deleteAllById(orphanedAuthEvidenceIds)
        }
        log.info("Retention: deleted {} channel session(s)", channels.size)
    }

    companion object {
        /** Fixed page size for [deleteExpiredJourneys]/[deleteExpiredChannels] (B4). */
        private const val RETENTION_BATCH_SIZE = 500

        private val TOOL_SESSION_RETENTION: Duration = Duration.ofHours(24)
        private val JOURNEY_RETENTION: Duration = Duration.ofDays(7)
        private val CHANNEL_SESSION_RETENTION: Duration = Duration.ofDays(30)
        private val SESSION_EVENT_RETENTION: Duration = Duration.ofDays(90)

        /**
         * The journey log is queried per channel session
         * ([com.example.dpop.orchestrator.journeylog.JourneyLogService.getLogForAccount] resolves
         * the channel set first), so outliving [CHANNEL_SESSION_RETENTION] buys nothing while
         * this is by far the highest-volume table in the system - one row per journey step, each
         * with a JSON `detail`. It is a debugging/demo trace, NOT the audit trail; that is
         * SessionEvent, which keeps its own, longer window.
         */
        private val JOURNEY_LOG_RETENTION: Duration = Duration.ofDays(30)

        /**
         * Two orders of magnitude beyond the longest window or lockout any throttle service uses
         * (15 minutes), so a sweep can never shorten an active budget - see
         * [AttemptThrottleRepository.deleteStaleCounters], which additionally refuses to touch a
         * row whose lock still runs.
         */
        private val ATTEMPT_THROTTLE_RETENTION: Duration = Duration.ofDays(7)
    }
}
