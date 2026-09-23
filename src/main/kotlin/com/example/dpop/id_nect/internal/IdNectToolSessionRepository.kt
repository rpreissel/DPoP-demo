package com.example.dpop.id_nect.internal

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface IdNectToolSessionRepository : JpaRepository<IdNectToolSession, UUID> {
    @Modifying
    @Query("delete from IdNectToolSession e where e.createdAt < :cutoff")
    fun deleteByCreatedAtBefore(@Param("cutoff") cutoff: Instant): Int
}
