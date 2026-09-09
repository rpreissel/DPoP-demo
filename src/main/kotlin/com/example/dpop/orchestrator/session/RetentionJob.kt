package com.example.dpop.orchestrator.session

import com.example.dpop.orchestrator.journey.AuthJourneyRepository
import com.example.dpop.orchestrator.kc.KeycloakAdminClient
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
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
 * ids are expected, not a defect). account.*, AuthSmsEnrollment and person/fsc_code belong to
 * the account, never touched here.
 */
@Component
class RetentionJob(
    private val toolSessionRepository: ToolSessionRepository,
    private val journeyRepository: AuthJourneyRepository,
    private val channelSessionRepository: ChannelSessionRepository,
    private val authContextRepository: AuthContextRepository,
    private val authEvidenceRepository: AuthEvidenceRepository,
    private val sessionEventRepository: SessionEventRepository,
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
        val journeyCutoff = now.minus(JOURNEY_RETENTION)
        val retainedJourneyIds = journeyRepository.findIdsForRetention(journeyCutoff)
        if (retainedJourneyIds.isNotEmpty()) {
            toolSessionRepository.deleteByJourneyIdIn(retainedJourneyIds)
            journeyRepository.deleteAllByIdInBatch(retainedJourneyIds)
        }

        val confirmedDeadKcChannels = confirmedDeadKcChannels(now)
        deleteChannels(confirmedDeadKcChannels)

        val expiredChannels = channelSessionRepository.findByExpiresAtBefore(now.minus(CHANNEL_SESSION_RETENTION))
            .filterNot { it.channelSessionId in confirmedDeadKcChannels.mapNotNull { c -> c.channelSessionId } }
        deleteChannels(expiredChannels)

        sessionEventRepository.deleteByCreatedAtBefore(now.minus(SESSION_EVENT_RETENTION))
    }

    /**
     * `KEYCLOAK` channels whose own (short, per-flow-run) TTL already passed AND whose durable
     * Keycloak session is affirmatively confirmed gone - safe to delete now instead of waiting out
     * [CHANNEL_SESSION_RETENTION] like every other channel (DPoP-demo-f9o.12, docs/ideen/
     * web-keycloak-kanal.md #11): Keycloak owns logout entirely and never tells the orchestrator
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
        private val TOOL_SESSION_RETENTION: Duration = Duration.ofHours(24)
        private val JOURNEY_RETENTION: Duration = Duration.ofDays(7)
        private val CHANNEL_SESSION_RETENTION: Duration = Duration.ofDays(30)
        private val SESSION_EVENT_RETENTION: Duration = Duration.ofDays(90)
    }
}
