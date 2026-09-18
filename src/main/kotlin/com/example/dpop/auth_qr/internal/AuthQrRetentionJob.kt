package com.example.dpop.auth_qr.internal

import com.example.dpop.auth_qr.internal.authqr.AuthQrToolSessionRepository
import com.example.dpop.auth_qr.internal.authqrlookup.AuthQrLookupToolSessionRepository
import com.example.dpop.auth_qr.internal.confirmqrlogin.ConfirmQrLoginToolSessionRepository
import com.example.dpop.auth_qr.internal.enrollqr.EnrollQrToolSessionRepository
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant

/**
 * Self-cleanup by age (docs/07-betrieb.md #3) of the tool-session-scoped working data and of pairing
 * requests past their own expiry. QrOptIn (the long-lived opt-in) belongs to the account and is
 * out of scope.
 */
@Component
class AuthQrRetentionJob(
    private val enrollToolSessionRepository: EnrollQrToolSessionRepository,
    private val authToolSessionRepository: AuthQrToolSessionRepository,
    private val authLookupToolSessionRepository: AuthQrLookupToolSessionRepository,
    private val confirmToolSessionRepository: ConfirmQrLoginToolSessionRepository,
    private val loginRequestRepository: QrLoginRequestRepository
) {

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 60_000)
    @Transactional
    fun cleanup() {
        val cutoff = Instant.now().minus(RETENTION)
        enrollToolSessionRepository.deleteByCreatedAtBefore(cutoff)
        authToolSessionRepository.deleteByCreatedAtBefore(cutoff)
        authLookupToolSessionRepository.deleteByCreatedAtBefore(cutoff)
        confirmToolSessionRepository.deleteByCreatedAtBefore(cutoff)
        loginRequestRepository.deleteByExpiresAtBefore(cutoff)
    }

    companion object {
        private val RETENTION: Duration = Duration.ofHours(24)
    }
}
