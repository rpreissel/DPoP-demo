package com.example.dpop.orchestrator.journeylog

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface JourneyLogRepository : JpaRepository<JourneyLogEntry, UUID> {
    /** The operator's view across every channel and account (`AdminJourneyLogController`), newest first. */
    fun findAllByOrderByCreatedAtDesc(pageable: Pageable): List<JourneyLogEntry>

    /**
     * Retention sweep (`RetentionJob`). A bulk statement rather than a derived `deleteBy...`:
     * this is the highest-volume table in the system (one row per journey step, with a JSON
     * `detail`), and the derived form would load every matching entry into the persistence
     * context first just to delete it row by row.
     */
    @Modifying
    @Query("delete from JourneyLogEntry e where e.createdAt < :cutoff")
    fun deleteByCreatedAtBefore(@Param("cutoff") cutoff: Instant): Int

    /**
     * Erasure path (`AccountDeletionService`). Two keys in ONE statement, both deliberate:
     *
     * Two keys, because entries written BEFORE the channel resolved an account carry a null
     * `accountId` and would survive an account-keyed delete.
     *
     * One statement, because every bulk mutation over this table auto-flushes the persistence
     * context first. Splitting the erasure in two let the second flush re-issue a write for an
     * entry the first statement had already deleted, which Hibernate reports as an optimistic
     * lock failure ("expected row count 1 but was 0") and the API surfaces as a spurious 409 on
     * the very request that asked for the deletion.
     *
     * `flushAutomatically`/`clearAutomatically` for the same reason, and they belong together:
     * the flush gets every pending change (the channel logouts above all) written BEFORE the
     * delete, so the clear cannot discard them; the clear then detaches the entries this
     * statement just removed. Without it they stay managed, and because `detail` is a mutable
     * JSON map Hibernate re-checks as dirty, the next query in the same request flushes an UPDATE
     * against rows that no longer exist - the same spurious 409, just later.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from JourneyLogEntry e where e.accountId = :accountId or e.channelSessionId in :channelSessionIds")
    fun deleteByAccountIdOrChannelSessionIdIn(
        @Param("accountId") accountId: Long,
        @Param("channelSessionIds") channelSessionIds: Collection<UUID>
    ): Int
}
