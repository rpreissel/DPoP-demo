package com.example.dpop.auth_password.internal
import com.example.dpop.auth_password.internal.authpasswordlookup.AuthPasswordLookupToolSessionRepository
import com.example.dpop.auth_password.internal.authpassworduse.AuthPasswordUseToolSessionRepository
import com.example.dpop.auth_password.internal.enrollpassword.EnrollPasswordToolSessionRepository

import com.example.dpop.tool_api.ToolSessionSweeper
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
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
) : ToolSessionSweeper {

    @Transactional
    override fun sweep(cutoff: Instant) {
        enrollToolSessionRepository.deleteByCreatedAtBefore(cutoff)
        authUseToolSessionRepository.deleteByCreatedAtBefore(cutoff)
        authLookupToolSessionRepository.deleteByCreatedAtBefore(cutoff)
    }

}
