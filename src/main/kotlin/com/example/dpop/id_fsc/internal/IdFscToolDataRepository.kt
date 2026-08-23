package com.example.dpop.id_fsc.internal

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
interface IdFscToolDataRepository : JpaRepository<IdFscToolData, UUID> {
    fun deleteByCreatedAtBefore(cutoff: Instant): Long
}
