package com.example.dpop.orchestrator.journey

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
interface AuthJourneyRepository : JpaRepository<AuthJourney, UUID> {
    /**
     * At most one RUNNING journey per channel (docs/07-betrieb.md #2). A parent waiting on a
     * sub-journey is SUSPENDED, not STARTED, which is what keeps this single-valued without a
     * second rule about which of the two to pick.
     */
    fun findFirstByChannelSessionIdAndLifecycleOrderByCreatedAtDesc(
        channelSessionId: UUID,
        lifecycle: JourneyLifecycle
    ): AuthJourney?

    /** Retention clock starts at consumedAt or expiresAt, whichever applies (docs/07-betrieb.md #3). */
    fun deleteByConsumedAtBeforeOrExpiresAtBefore(consumedCutoff: Instant, expiresCutoff: Instant): Long
}
