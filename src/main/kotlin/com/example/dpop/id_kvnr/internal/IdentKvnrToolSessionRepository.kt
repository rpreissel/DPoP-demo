package com.example.dpop.id_kvnr.internal

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
interface IdentKvnrToolSessionRepository : JpaRepository<IdentKvnrToolSession, UUID> {
    @Modifying
    @Query("delete from IdentKvnrToolSession e where e.createdAt < :cutoff")
    fun deleteByCreatedAtBefore(@Param("cutoff") cutoff: Instant): Int
}
