package com.example.dpop.orchestrator.session

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
interface ProcessSessionRepository : JpaRepository<ProcessSession, UUID> {
    /** At most one active process per channel (docs/07-betrieb.md #2). */
    fun findFirstByChannelSessionIdAndStateOrderByCreatedAtDesc(
        channelSessionId: UUID,
        state: ProcessState
    ): ProcessSession?

    /** Retention clock starts at consumedAt or expiresAt, whichever applies (docs/07-betrieb.md #3). */
    fun deleteByConsumedAtBeforeOrExpiresAtBefore(consumedCutoff: Instant, expiresCutoff: Instant): Long
}
