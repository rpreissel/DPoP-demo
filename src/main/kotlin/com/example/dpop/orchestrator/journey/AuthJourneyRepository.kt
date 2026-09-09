package com.example.dpop.orchestrator.journey

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
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
    @Query(
        """
        select j.journeyId from AuthJourney j
        where j.consumedAt < :cutoff or j.expiresAt < :cutoff
        """
    )
    fun findIdsForRetention(cutoff: Instant): List<UUID>

    fun deleteByConsumedAtBeforeOrExpiresAtBefore(consumedCutoff: Instant, expiresCutoff: Instant): Long

    /**
     * Companion to the age-based cleanup above: a ChannelSession about to be deleted
     * (RetentionJob.deleteChannels) can still have a not-yet-aged-out AuthJourney pointing at it
     * (e.g. a confirmed-dead KEYCLOAK channel whose own TTL is much shorter than the journey
     * retention window) - must be cleared first or the FK_AUTH_JOURNEY_CHANNEL_SESSION
     * constraint rejects the delete.
     */
    fun deleteByChannelSessionIdIn(channelSessionIds: Collection<UUID>): Long

    @Query("select j.journeyId from AuthJourney j where j.channelSessionId in :channelSessionIds")
    fun findIdsByChannelSessionIdIn(channelSessionIds: Collection<UUID>): List<UUID>
}
