package com.example.dpop.orchestrator.session

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
interface ChannelSessionRepository : JpaRepository<ChannelSession, UUID> {
    fun findByExpiresAtBefore(cutoff: Instant): List<ChannelSession>

    /**
     * Every already-expired channel of one [ChannelSession.Channel] - `RetentionJob`
     * (DPoP-demo-f9o.12) uses this for `KEYCLOAK` channels only, to find candidates for an
     * early, Keycloak-session-liveness-confirmed cleanup ahead of the normal retention window
     * [findByExpiresAtBefore] otherwise waits out for every channel alike.
     */
    fun findByChannelAndExpiresAtBefore(channel: ChannelSession.Channel, cutoff: Instant): List<ChannelSession>

    /** Every channel this account was ever bound to - used to invalidate them all on account deletion. */
    fun findByAccountId(accountId: Long): List<ChannelSession>
}
