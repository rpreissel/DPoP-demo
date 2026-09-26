package com.example.dpop.auth_email.internal
import com.example.dpop.auth_email.internal.authemaillookup.AuthEmailLookupToolSessionRepository
import com.example.dpop.auth_email.internal.authemail.AuthEmailToolSessionRepository
import com.example.dpop.auth_email.internal.confirmemail.ConfirmEmailToolSessionRepository
import com.example.dpop.auth_email.internal.enrollemail.EnrollEmailToolSessionRepository

import com.example.dpop.tool_api.ToolSessionSweeper
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/** Self-cleanup by age (docs/07-betrieb.md #3), mirroring AuthSmsRetentionJob. */
@Component
class AuthEmailRetentionJob(
    private val confirmToolSessionRepository: ConfirmEmailToolSessionRepository,
    private val enrollToolSessionRepository: EnrollEmailToolSessionRepository,
    private val authUseToolSessionRepository: AuthEmailToolSessionRepository,
    private val authLookupToolSessionRepository: AuthEmailLookupToolSessionRepository
) : ToolSessionSweeper {

    @Transactional
    override fun sweep(cutoff: Instant) {
        confirmToolSessionRepository.deleteByCreatedAtBefore(cutoff)
        enrollToolSessionRepository.deleteByCreatedAtBefore(cutoff)
        authUseToolSessionRepository.deleteByCreatedAtBefore(cutoff)
        authLookupToolSessionRepository.deleteByCreatedAtBefore(cutoff)
    }

}
