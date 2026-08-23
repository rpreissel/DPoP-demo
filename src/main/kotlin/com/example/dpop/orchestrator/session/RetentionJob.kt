package com.example.dpop.orchestrator.session

import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant

/**
 * Retention for the orchestrator's own possession chain (docs/07-betrieb.md #3): cleaned from
 * the inside out (ToolSession -> ProcessSession -> ChannelSession+AuthContext) so a row's FK
 * target is already gone by the time it would be deleted. SessionEvent is independent - it
 * deliberately outlives the sessions it references (dangling ids are expected, not a defect).
 * account.*, AuthSmsEnrollment and person/fsc_code belong to the account, never touched here.
 */
@Component
class RetentionJob(
    private val toolSessionRepository: ToolSessionRepository,
    private val processSessionRepository: ProcessSessionRepository,
    private val channelSessionRepository: ChannelSessionRepository,
    private val authContextRepository: AuthContextRepository,
    private val sessionEventRepository: SessionEventRepository
) {

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 60_000)
    @Transactional
    fun cleanup() {
        val now = Instant.now()

        toolSessionRepository.deleteByExpiresAtBefore(now.minus(TOOL_SESSION_RETENTION))
        processSessionRepository.deleteByConsumedAtBeforeOrExpiresAtBefore(
            now.minus(PROCESS_SESSION_RETENTION), now.minus(PROCESS_SESSION_RETENTION)
        )

        val expiredChannels = channelSessionRepository.findByExpiresAtBefore(now.minus(CHANNEL_SESSION_RETENTION))
        val orphanedAuthContextIds = expiredChannels.mapNotNull { it.authContextId }
        channelSessionRepository.deleteAll(expiredChannels)
        if (orphanedAuthContextIds.isNotEmpty()) {
            authContextRepository.deleteAllById(orphanedAuthContextIds)
        }

        sessionEventRepository.deleteByCreatedAtBefore(now.minus(SESSION_EVENT_RETENTION))
    }

    companion object {
        private val TOOL_SESSION_RETENTION: Duration = Duration.ofHours(24)
        private val PROCESS_SESSION_RETENTION: Duration = Duration.ofDays(7)
        private val CHANNEL_SESSION_RETENTION: Duration = Duration.ofDays(30)
        private val SESSION_EVENT_RETENTION: Duration = Duration.ofDays(90)
    }
}
