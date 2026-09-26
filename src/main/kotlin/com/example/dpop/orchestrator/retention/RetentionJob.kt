package com.example.dpop.orchestrator.retention

import com.example.dpop.orchestrator.journey.AuthJourneyRepository
import com.example.dpop.orchestrator.journeytrace.JourneyTraceRepository
import com.example.dpop.orchestrator.session.AttemptThrottleRepository
import com.example.dpop.orchestrator.session.AuthContextRepository
import com.example.dpop.orchestrator.session.AuthEvidenceRepository
import com.example.dpop.orchestrator.session.ChannelSession
import com.example.dpop.orchestrator.session.ChannelSessionRepository
import com.example.dpop.orchestrator.session.ToolSessionRepository
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant

/**
 * Retention for the orchestrator's own possession chain (docs/07-betrieb.md #3), in one
 * transaction, cleaned from the inside out (ToolSession -> AuthJourney ->
 * ChannelSession+AuthContext+AuthEvidence) so a row's FK target is already gone by the time it
 * would be deleted. The age-based AuthJourney sweep only catches journeys old enough on their own
 * clock, so [deleteChannels] additionally clears any journeys still pointing at the channels it is
 * about to delete. SessionEvent is independent - it deliberately outlives the sessions it
 * references (dangling ids are expected, not a defect). The same holds for JourneyTraceEntry and
 * AttemptThrottle: both are keyed by ids they do not constrain, so both are swept purely by age -
 * and both MUST be swept, because neither is bounded by anything else. account.*,
 * AuthSmsEnrollment and ext_personenverzeichnis.person/freischaltcode belong to the account, never
 * touched here.
 *
 * `KEYCLOAK` channels follow the same window as every other channel. Until 2026-09-26 an expired
 * one was deleted early once Keycloak's Admin API confirmed its session gone - one network call per
 * candidate, unbounded, every hour (review 2026-09-26, B-3). Keycloak now reports its logouts
 * (`KcChannelService.signedOutAtKeycloak`), which ends the live channels of that session directly.
 */
@Component
class RetentionJob(
    private val toolSessionRepository: ToolSessionRepository,
    private val journeyRepository: AuthJourneyRepository,
    private val channelSessionRepository: ChannelSessionRepository,
    private val authContextRepository: AuthContextRepository,
    private val authEvidenceRepository: AuthEvidenceRepository,
    private val journeyTraceRepository: JourneyTraceRepository,
    private val attemptThrottleRepository: AttemptThrottleRepository
) {
    private val log = LoggerFactory.getLogger(RetentionJob::class.java)

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 60_000)
    @Transactional
    fun cleanup() {
        val now = Instant.now()
        toolSessionRepository.deleteByExpiresAtBefore(now.minus(TOOL_SESSION_RETENTION))
        deleteExpiredJourneys(now.minus(JOURNEY_RETENTION))
        deleteExpiredChannels(now.minus(CHANNEL_SESSION_RETENTION))

        val journeyTraceEntries = journeyTraceRepository.deleteByCreatedAtBefore(now.minus(JOURNEY_TRACE_RETENTION))
        val staleCounters = attemptThrottleRepository.deleteStaleCounters(now.minus(ATTEMPT_THROTTLE_RETENTION), now)
        if (journeyTraceEntries > 0 || staleCounters > 0) {
            log.info(
                "Retention: deleted {} journey trace entry/entries and {} attempt throttle counter(s)",
                journeyTraceEntries,
                staleCounters
            )
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
        // One statement per table and batch (review 2026-09-26, B-7) - deleteAll/deleteAllById
        // would load and delete row by row.
        channelSessionRepository.deleteAllInBatch(channels)
        if (orphanedAuthContextIds.isNotEmpty()) {
            authContextRepository.deleteAllByIdInBatch(orphanedAuthContextIds)
        }
        if (orphanedAuthEvidenceIds.isNotEmpty()) {
            authEvidenceRepository.deleteAllByIdInBatch(orphanedAuthEvidenceIds)
        }
        log.info("Retention: deleted {} channel session(s)", channels.size)
    }

    companion object {
        /** Fixed page size for [deleteExpiredJourneys]/[deleteExpiredChannels] (B4). */
        private const val RETENTION_BATCH_SIZE = 500

        private val TOOL_SESSION_RETENTION: Duration = Duration.ofHours(24)
        private val JOURNEY_RETENTION: Duration = Duration.ofDays(7)
        /**
         * As long as [JOURNEY_TRACE_RETENTION] and no longer (review 2026-09-26, B-7): the trace is
         * read per channel session, the channel has no other reason to outlive its expiry, and one
         * row per app start makes this a large table.
         */
        private val CHANNEL_SESSION_RETENTION: Duration = Duration.ofDays(14)

        /**
         * The journey trace is read per channel session (the admin view groups by it), so
         * outliving [CHANNEL_SESSION_RETENTION] buys nothing while
         * this is by far the highest-volume table in the system - one row per journey step, each
         * with a JSON `detail`. It is a debugging/demo trace, NOT the change log; that is
         * `account.change_log` (ADR-39), which keeps its own, much longer window.
         */
        private val JOURNEY_TRACE_RETENTION: Duration = Duration.ofDays(14)

        /**
         * Two orders of magnitude beyond the longest window or lockout any throttle service uses
         * (15 minutes), so a sweep can never shorten an active budget - see
         * [AttemptThrottleRepository.deleteStaleCounters], which additionally refuses to touch a
         * row whose lock still runs.
         */
        private val ATTEMPT_THROTTLE_RETENTION: Duration = Duration.ofDays(7)
    }
}
