package com.example.dpop.auth_email.internal

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
interface AuthEmailUseToolDataRepository : JpaRepository<AuthEmailUseToolData, UUID> {
    fun deleteByCreatedAtBefore(cutoff: Instant): Long
}
