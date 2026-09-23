package com.example.dpop.auth_qr.internal

import com.example.dpop.auth_qr.internal.authqr.AuthQrToolSessionRepository
import com.example.dpop.auth_qr.internal.authqrlookup.AuthQrLookupToolSessionRepository
import com.example.dpop.auth_qr.internal.confirmqrlogin.ConfirmQrLoginToolSessionRepository
import com.example.dpop.auth_qr.internal.enrollqr.EnrollQrToolSessionRepository
import com.example.dpop.tool_api.ToolSessionSweeper
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
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
) : ToolSessionSweeper {

    @Transactional
    override fun sweep(cutoff: Instant) {
        enrollToolSessionRepository.deleteByCreatedAtBefore(cutoff)
        authToolSessionRepository.deleteByCreatedAtBefore(cutoff)
        authLookupToolSessionRepository.deleteByCreatedAtBefore(cutoff)
        confirmToolSessionRepository.deleteByCreatedAtBefore(cutoff)
        loginRequestRepository.deleteByExpiresAtBefore(cutoff)
    }

}
