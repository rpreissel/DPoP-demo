package com.example.dpop.id_eid.internal

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
interface IdEidToolDataRepository : JpaRepository<IdEidToolData, UUID> {
    fun deleteByCreatedAtBefore(cutoff: Instant): Long
}
