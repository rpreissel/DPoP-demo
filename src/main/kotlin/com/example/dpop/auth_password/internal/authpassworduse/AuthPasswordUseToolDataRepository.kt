package com.example.dpop.auth_password.internal.authpassworduse

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
interface AuthPasswordUseToolDataRepository : JpaRepository<AuthPasswordUseToolData, UUID> {
    fun deleteByCreatedAtBefore(cutoff: Instant): Long
}
