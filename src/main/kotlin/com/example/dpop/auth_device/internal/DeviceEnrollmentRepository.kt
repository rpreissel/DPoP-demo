package com.example.dpop.auth_device.internal

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface DeviceEnrollmentRepository : JpaRepository<DeviceEnrollment, Long> {
    /** Idempotency check for re-enrollment of the same physical key (EnrollDeviceToolHandler.patch). */
    fun findByThumbprint(thumbprint: String): DeviceEnrollment?
}
