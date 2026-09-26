package com.example.dpop.orchestrator.session

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
interface ChannelSessionRepository : JpaRepository<ChannelSession, UUID> {
    /**
     * [pageable] bounds one retention batch (`RetentionJob`, B4) - the caller deletes every
     * returned row before asking again, so page 0 always reflects the current remaining backlog.
     */
    fun findByExpiresAtBefore(cutoff: Instant, pageable: Pageable): List<ChannelSession>

    /** Every channel this account was ever bound to - used to invalidate them all on account deletion. */
    fun findByAccountId(accountId: Long): List<ChannelSession>
}
