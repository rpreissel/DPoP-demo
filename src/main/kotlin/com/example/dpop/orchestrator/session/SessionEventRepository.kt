package com.example.dpop.orchestrator.session

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
interface SessionEventRepository : JpaRepository<SessionEvent, UUID> {
    fun deleteByCreatedAtBefore(cutoff: Instant): Long
}
