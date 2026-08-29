package com.example.dpop.auth_device.internal

import com.example.dpop.tool_api.EnrollmentCleanup
import com.example.dpop.tool_spi.EnrollmentRef
import org.springframework.stereotype.Component

@Component
class DeviceEnrollmentCleanup(
    private val enrollmentRepository: DeviceEnrollmentRepository
) : EnrollmentCleanup {
    override val enrollmentType = "device_enrollment"

    override fun delete(enrollmentRef: EnrollmentRef) {
        enrollmentRepository.deleteById(enrollmentRef.id.toLong())
    }
}
