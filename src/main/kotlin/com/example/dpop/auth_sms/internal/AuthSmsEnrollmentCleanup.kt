package com.example.dpop.auth_sms.internal

import com.example.dpop.tool_api.EnrollmentCleanup
import com.example.dpop.tool_spi.EnrollmentRef
import org.springframework.stereotype.Component

@Component
class AuthSmsEnrollmentCleanup(
    private val enrollmentRepository: AuthSmsEnrollmentRepository
) : EnrollmentCleanup {
    override val enrollmentType = "auth_sms_enrollment"

    override fun delete(enrollmentRef: EnrollmentRef) {
        enrollmentRepository.deleteById(enrollmentRef.id.toLong())
    }
}
