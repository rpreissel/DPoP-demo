package com.example.dpop.auth_sms.internal

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
interface AuthSmsLookupToolDataRepository : JpaRepository<AuthSmsLookupToolData, UUID> {
    fun deleteByCreatedAtBefore(cutoff: Instant): Long
}
