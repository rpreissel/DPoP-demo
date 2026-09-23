package com.example.dpop.auth_device.internal

import com.example.dpop.auth_device.internal.authdevice.AuthDeviceToolSessionRepository
import com.example.dpop.auth_device.internal.enrolldevice.EnrollDeviceToolSessionRepository
import com.example.dpop.tool_api.ToolSessionSweeper
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Self-cleanup by age (docs/07-betrieb.md #3) of the tool-session-scoped working data. DeviceEnrollment
 * (the long-lived credential) belongs to the account and is out of scope.
 */
@Component
class AuthDeviceRetentionJob(
    private val enrollToolSessionRepository: EnrollDeviceToolSessionRepository,
    private val authToolSessionRepository: AuthDeviceToolSessionRepository
) : ToolSessionSweeper {

    @Transactional
    override fun sweep(cutoff: Instant) {
        enrollToolSessionRepository.deleteByCreatedAtBefore(cutoff)
        authToolSessionRepository.deleteByCreatedAtBefore(cutoff)
    }

}
