package com.example.dpop.auth_email.internal
import com.example.dpop.auth_email.internal.authemaillookup.AuthEmailLookupToolSessionRepository
import com.example.dpop.auth_email.internal.authemailuse.AuthEmailUseToolSessionRepository
import com.example.dpop.auth_email.internal.enrollemail.EnrollEmailToolSessionRepository

import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant

/** Self-cleanup by age (docs/07-betrieb.md #3), mirroring AuthSmsRetentionJob. */
@Component
class AuthEmailRetentionJob(
    private val enrollToolSessionRepository: EnrollEmailToolSessionRepository,
    private val authUseToolSessionRepository: AuthEmailUseToolSessionRepository,
    private val authLookupToolSessionRepository: AuthEmailLookupToolSessionRepository
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
