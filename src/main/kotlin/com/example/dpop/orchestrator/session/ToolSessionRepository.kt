package com.example.dpop.orchestrator.session

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
interface ToolSessionRepository : JpaRepository<ToolSession, UUID> {
    /** Retention clock starts at expiresAt, not createdAt (docs/07-betrieb.md #3). */
    fun deleteByExpiresAtBefore(cutoff: Instant): Long

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from ToolSession t where t.journeyId in :journeyIds")
    fun deleteByJourneyIdIn(@Param("journeyIds") journeyIds: Collection<UUID>): Int
}
