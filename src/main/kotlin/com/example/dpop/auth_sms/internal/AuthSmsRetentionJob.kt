package com.example.dpop.auth_sms.internal
import com.example.dpop.auth_sms.internal.authsmslookup.AuthSmsLookupToolDataRepository
import com.example.dpop.auth_sms.internal.authsmsuse.AuthSmsUseToolDataRepository
import com.example.dpop.auth_sms.internal.enrollsms.EnrollSmsToolDataRepository

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
    private val enrollToolDataRepository: EnrollSmsToolDataRepository,
    private val authUseToolDataRepository: AuthSmsUseToolDataRepository,
    private val authLookupToolDataRepository: AuthSmsLookupToolDataRepository
) {

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 60_000)
    @Transactional
    fun cleanup() {
        val cutoff = Instant.now().minus(RETENTION)
        enrollToolDataRepository.deleteByCreatedAtBefore(cutoff)
        authUseToolDataRepository.deleteByCreatedAtBefore(cutoff)
        authLookupToolDataRepository.deleteByCreatedAtBefore(cutoff)
    }

    companion object {
        private val RETENTION: Duration = Duration.ofHours(24)
    }
}
