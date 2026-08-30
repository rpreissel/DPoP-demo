package com.example.dpop.auth_email.internal
import com.example.dpop.auth_email.internal.authemaillookup.AuthEmailLookupToolDataRepository
import com.example.dpop.auth_email.internal.authemailuse.AuthEmailUseToolDataRepository
import com.example.dpop.auth_email.internal.enrollemail.EnrollEmailToolDataRepository

import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant

/** Self-cleanup by age (docs/07-betrieb.md #3), mirroring AuthSmsRetentionJob. */
@Component
class AuthEmailRetentionJob(
    private val enrollToolDataRepository: EnrollEmailToolDataRepository,
    private val authUseToolDataRepository: AuthEmailUseToolDataRepository,
    private val authLookupToolDataRepository: AuthEmailLookupToolDataRepository
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
