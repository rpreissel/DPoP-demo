package com.example.dpop.auth_password.internal.authpasswordlookup

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
interface AuthPasswordLookupToolDataRepository : JpaRepository<AuthPasswordLookupToolData, UUID> {
    fun deleteByCreatedAtBefore(cutoff: Instant): Long
}
