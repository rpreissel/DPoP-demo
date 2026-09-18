package com.example.dpop.auth_device.internal

import com.example.dpop.auth_device.internal.authdevice.AuthDeviceToolSessionRepository
import com.example.dpop.auth_device.internal.enrolldevice.EnrollDeviceToolSessionRepository
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant

/**
 * Self-cleanup by age (docs/07-betrieb.md #3) of the tool-session-scoped working data. DeviceEnrollment
 * (the long-lived credential) belongs to the account and is out of scope.
 */
@Component
class AuthDeviceRetentionJob(
    private val enrollToolSessionRepository: EnrollDeviceToolSessionRepository,
    private val authToolSessionRepository: AuthDeviceToolSessionRepository
) {

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 60_000)
    @Transactional
    fun cleanup() {
        val cutoff = Instant.now().minus(RETENTION)
        enrollToolSessionRepository.deleteByCreatedAtBefore(cutoff)
        authToolSessionRepository.deleteByCreatedAtBefore(cutoff)
    }

    companion object {
        private val RETENTION: Duration = Duration.ofHours(24)
    }
}
