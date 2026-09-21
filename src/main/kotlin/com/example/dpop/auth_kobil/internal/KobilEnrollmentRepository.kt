package com.example.dpop.auth_kobil.internal

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface KobilEnrollmentRepository : JpaRepository<KobilEnrollment, Long> {
    /** Idempotency check for a re-run of the same activation (EnrollKobilToolHandler.patch). */
    fun findByKobilUserId(kobilUserId: String): KobilEnrollment?
}
