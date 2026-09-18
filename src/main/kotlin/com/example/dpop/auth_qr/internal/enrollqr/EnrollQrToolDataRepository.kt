package com.example.dpop.auth_qr.internal.enrollqr

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.repository.query.Param
import org.springframework.data.jpa.repository.Query
import org.springframework.data.jpa.repository.Modifying
import java.time.Instant
import java.util.UUID

interface EnrollQrToolDataRepository : JpaRepository<EnrollQrToolData, UUID> {
    @Modifying
    @Query("delete from EnrollQrToolData e where e.createdAt < :cutoff")
    fun deleteByCreatedAtBefore(@Param("cutoff") cutoff: Instant): Int
}
