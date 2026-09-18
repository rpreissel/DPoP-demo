package com.example.dpop.auth_password.internal
import com.example.dpop.auth_password.internal.authpasswordlookup.AuthPasswordLookupToolSessionRepository
import com.example.dpop.auth_password.internal.authpassworduse.AuthPasswordUseToolSessionRepository
import com.example.dpop.auth_password.internal.enrollpassword.EnrollPasswordToolSessionRepository

import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant

/**
 * Self-cleanup by age (docs/07-betrieb.md #3), mirroring AuthSmsRetentionJob. AuthPasswordEnrollment
 * (the confirmed, long-lived credential) is explicitly out of scope - it belongs to the account,
 * not the session.
 */
@Component
class AuthPasswordRetentionJob(
    private val enrollToolSessionRepository: EnrollPasswordToolSessionRepository,
    private val authUseToolSessionRepository: AuthPasswordUseToolSessionRepository,
    private val authLookupToolSessionRepository: AuthPasswordLookupToolSessionRepository
) {

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 60_000)
    @Transactional
    fun cleanup() {
        val cutoff = Instant.now().minus(RETENTION)
        enrollToolSessionRepository.deleteByCreatedAtBefore(cutoff)
        authUseToolSessionRepository.deleteByCreatedAtBefore(cutoff)
        authLookupToolSessionRepository.deleteByCreatedAtBefore(cutoff)
    }

    companion object {
        private val RETENTION: Duration = Duration.ofHours(24)
    }
}
