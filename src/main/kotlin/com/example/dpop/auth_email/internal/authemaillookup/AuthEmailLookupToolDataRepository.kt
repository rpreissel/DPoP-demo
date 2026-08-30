package com.example.dpop.auth_email.internal.authemaillookup

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
interface AuthEmailLookupToolDataRepository : JpaRepository<AuthEmailLookupToolData, UUID> {
    fun deleteByCreatedAtBefore(cutoff: Instant): Long
}
