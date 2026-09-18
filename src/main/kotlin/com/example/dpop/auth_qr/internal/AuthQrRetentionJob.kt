package com.example.dpop.auth_qr.internal

import com.example.dpop.auth_qr.internal.authqr.AuthQrToolDataRepository
import com.example.dpop.auth_qr.internal.authqrlookup.AuthQrLookupToolDataRepository
import com.example.dpop.auth_qr.internal.confirmqrlogin.ConfirmQrLoginToolDataRepository
import com.example.dpop.auth_qr.internal.enrollqr.EnrollQrToolDataRepository
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant

/**
 * Self-cleanup by age (docs/07-betrieb.md #3) of the attempt-scoped tool data and of pairing
 * requests past their own expiry. QrOptIn (the long-lived opt-in) belongs to the account and is
 * out of scope.
 */
@Component
class AuthQrRetentionJob(
    private val enrollToolDataRepository: EnrollQrToolDataRepository,
    private val authToolDataRepository: AuthQrToolDataRepository,
    private val authLookupToolDataRepository: AuthQrLookupToolDataRepository,
    private val confirmToolDataRepository: ConfirmQrLoginToolDataRepository,
    private val loginRequestRepository: QrLoginRequestRepository
) {

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 60_000)
    @Transactional
    fun cleanup() {
        val cutoff = Instant.now().minus(RETENTION)
        enrollToolDataRepository.deleteByCreatedAtBefore(cutoff)
        authToolDataRepository.deleteByCreatedAtBefore(cutoff)
        authLookupToolDataRepository.deleteByCreatedAtBefore(cutoff)
        confirmToolDataRepository.deleteByCreatedAtBefore(cutoff)
        loginRequestRepository.deleteByExpiresAtBefore(cutoff)
    }

    companion object {
        private val RETENTION: Duration = Duration.ofHours(24)
    }
}
