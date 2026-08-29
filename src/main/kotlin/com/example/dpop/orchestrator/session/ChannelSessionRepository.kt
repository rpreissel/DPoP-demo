package com.example.dpop.orchestrator.session

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
interface ChannelSessionRepository : JpaRepository<ChannelSession, UUID> {
    fun findByExpiresAtBefore(cutoff: Instant): List<ChannelSession>

    /** Every channel this account was ever bound to - used to invalidate them all on account deletion. */
    fun findByAccountId(accountId: Long): List<ChannelSession>
}
