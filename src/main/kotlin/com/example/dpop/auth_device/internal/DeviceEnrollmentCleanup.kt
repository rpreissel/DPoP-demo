package com.example.dpop.auth_device.internal

import com.example.dpop.auth_device.DEVICE_ENROLLMENT_TYPE
import com.example.dpop.tool_api.EnrollmentCleanup
import com.example.dpop.tool_spi.EnrollmentRef
import org.springframework.stereotype.Component

@Component
class DeviceEnrollmentCleanup(
    private val enrollmentRepository: DeviceEnrollmentRepository
) : EnrollmentCleanup {
    override val enrollmentType = DEVICE_ENROLLMENT_TYPE

    override fun delete(enrollmentRef: EnrollmentRef) {
        enrollmentRepository.deleteById(enrollmentRef.id.toLong())
    }
}
