package com.example.dpop.auth_kobil.internal.enrollkobil

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
interface EnrollKobilToolSessionRepository : JpaRepository<EnrollKobilToolSession, UUID> {
    @Modifying
    @Query("delete from EnrollKobilToolSession e where e.createdAt < :cutoff")
    fun deleteByCreatedAtBefore(@Param("cutoff") cutoff: Instant): Int
}
