package com.example.dpop.auth_kobil.internal.authkobil

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
interface AuthKobilToolSessionRepository : JpaRepository<AuthKobilToolSession, UUID> {
    @Modifying
    @Query("delete from AuthKobilToolSession a where a.createdAt < :cutoff")
    fun deleteByCreatedAtBefore(@Param("cutoff") cutoff: Instant): Int
}
