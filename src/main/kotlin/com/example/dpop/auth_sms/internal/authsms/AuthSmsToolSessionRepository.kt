package com.example.dpop.auth_sms.internal.authsmsuse

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.repository.query.Param
import org.springframework.data.jpa.repository.Query
import org.springframework.data.jpa.repository.Modifying
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
interface AuthSmsUseToolSessionRepository : JpaRepository<AuthSmsUseToolSession, UUID> {
    @Modifying
    @Query("delete from AuthSmsUseToolSession e where e.createdAt < :cutoff")
    fun deleteByCreatedAtBefore(@Param("cutoff") cutoff: Instant): Int
}
