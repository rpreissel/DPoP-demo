package com.example.dpop.auth_password.internal

import com.example.dpop.auth_password.PASSWORD_ENROLLMENT_TYPE
import com.example.dpop.tool_api.EnrollmentCleanup
import com.example.dpop.tool_spi.EnrollmentRef
import org.springframework.stereotype.Component

@Component
class AuthPasswordEnrollmentCleanup(
    private val enrollmentRepository: AuthPasswordEnrollmentRepository
) : EnrollmentCleanup {
    override val enrollmentType = PASSWORD_ENROLLMENT_TYPE

    override fun delete(enrollmentRef: EnrollmentRef) {
        enrollmentRepository.deleteById(enrollmentRef.id.toLong())
    }
}
