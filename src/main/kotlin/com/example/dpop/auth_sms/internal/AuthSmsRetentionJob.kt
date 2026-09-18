package com.example.dpop.auth_sms.internal
import com.example.dpop.auth_sms.internal.authsmslookup.AuthSmsLookupToolSessionRepository
import com.example.dpop.auth_sms.internal.authsmsuse.AuthSmsUseToolSessionRepository
import com.example.dpop.auth_sms.internal.enrollsms.EnrollSmsToolSessionRepository

import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant

/**
 * Self-cleanup by age (docs/07-betrieb.md #3): TAN hashes and unconfirmed phone numbers are
 * pointless after the process moved on. AuthSmsEnrollment (the confirmed, long-lived record)
 * is explicitly out of scope - it belongs to the account, not the session.
 */
@Component
class AuthSmsRetentionJob(
    private val enrollToolSessionRepository: EnrollSmsToolSessionRepository,
    private val authUseToolSessionRepository: AuthSmsUseToolSessionRepository,
    private val authLookupToolSessionRepository: AuthSmsLookupToolSessionRepository
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
