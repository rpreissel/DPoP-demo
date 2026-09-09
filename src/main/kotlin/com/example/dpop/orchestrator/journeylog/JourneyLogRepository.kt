package com.example.dpop.orchestrator.journeylog

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface JourneyLogRepository : JpaRepository<JourneyLogEntry, UUID> {
    fun findByBindingKeyRefOrderByCreatedAtDesc(bindingKeyRef: String): List<JourneyLogEntry>

    fun findByAccountIdOrderByCreatedAtDesc(accountId: Long): List<JourneyLogEntry>

    /**
     * Keyed on channelSessionId, not the entry's own [JourneyLogEntry.accountId] - an entry logged
     * BEFORE this channel's account was bound (e.g. the journey's own "Started") never gets that
     * field backfilled, so filtering on it directly would silently truncate every journey to
     * "from binding onward" instead of showing it whole. [JourneyLogService.getLogForAccount]
     * resolves the channel set from `ChannelSessionRepository.findByAccountId` first and queries
     * this by that instead.
     */
    fun findByChannelSessionIdInOrderByCreatedAtDesc(channelSessionIds: Collection<UUID>): List<JourneyLogEntry>
}
