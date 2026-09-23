package com.example.dpop.auth_sms.internal
import com.example.dpop.auth_sms.internal.authsmslookup.AuthSmsLookupToolSessionRepository
import com.example.dpop.auth_sms.internal.authsmsuse.AuthSmsUseToolSessionRepository
import com.example.dpop.auth_sms.internal.enrollsms.EnrollSmsToolSessionRepository

import com.example.dpop.tool_api.ToolSessionSweeper
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
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
) : ToolSessionSweeper {

    @Transactional
    override fun sweep(cutoff: Instant) {
        enrollToolSessionRepository.deleteByCreatedAtBefore(cutoff)
        authUseToolSessionRepository.deleteByCreatedAtBefore(cutoff)
        authLookupToolSessionRepository.deleteByCreatedAtBefore(cutoff)
    }

}
