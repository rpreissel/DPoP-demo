package com.example.dpop.auth_password.internal.enrollpassword

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
interface EnrollPasswordToolDataRepository : JpaRepository<EnrollPasswordToolData, UUID> {
    fun deleteByCreatedAtBefore(cutoff: Instant): Long
}
