package com.example.dpop.auth_kobil.internal

import com.example.dpop.auth_kobil.internal.authkobil.AuthKobilToolSessionRepository
import com.example.dpop.auth_kobil.internal.enrollkobil.EnrollKobilToolSessionRepository
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant

/**
 * Self-cleanup by age (docs/07-betrieb.md #3) of the tool-session-scoped working data - which
 * here includes a PIN and an unlock secret in the clear while a setup is running, so the deadline
 * matters more than usual. [KobilEnrollment], the long-lived credential, belongs to the account
 * and is out of scope; so is everything in `kobil_mock`, which is a foreign system.
 */
@Component
class AuthKobilRetentionJob(
    private val enrollToolSessionRepository: EnrollKobilToolSessionRepository,
    private val authToolSessionRepository: AuthKobilToolSessionRepository,
) {

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 60_000)
    @Transactional
    fun cleanup() {
        val cutoff = Instant.now().minus(RETENTION)
        enrollToolSessionRepository.deleteByCreatedAtBefore(cutoff)
        authToolSessionRepository.deleteByCreatedAtBefore(cutoff)
    }

    companion object {
        private val RETENTION: Duration = Duration.ofHours(24)
    }
}
