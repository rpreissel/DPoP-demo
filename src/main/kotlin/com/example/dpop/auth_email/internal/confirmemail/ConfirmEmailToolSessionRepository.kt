package com.example.dpop.auth_email.internal.confirmemail

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.repository.query.Param
import org.springframework.data.jpa.repository.Query
import org.springframework.data.jpa.repository.Modifying
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
interface ConfirmEmailToolSessionRepository : JpaRepository<ConfirmEmailToolSession, UUID> {
    @Modifying
    @Query("delete from ConfirmEmailToolSession e where e.createdAt < :cutoff")
    fun deleteByCreatedAtBefore(@Param("cutoff") cutoff: Instant): Int
}
