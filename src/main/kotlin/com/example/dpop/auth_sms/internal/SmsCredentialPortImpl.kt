package com.example.dpop.auth_sms.internal

import com.example.dpop.auth_sms.SMS_ENROLLMENT_TYPE
import com.example.dpop.tool_api.SmsCredentialPort
import com.example.dpop.tool_spi.EnrollmentRef
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Implements [SmsCredentialPort] for callers outside a Channel/ToolSession - writes exactly the
 * same enrollment row [EnrollSmsToolHandler][com.example.dpop.auth_sms.internal.enrollsms.EnrollSmsToolHandler]
 * writes once a TAN has been confirmed, just without the ToolSession indirection.
 */
@Component
internal class SmsCredentialPortImpl(
    private val enrollmentRepository: AuthSmsEnrollmentRepository
) : SmsCredentialPort {

    @Transactional
    override fun enroll(phoneNumber: String): EnrollmentRef {
        // Same normalization the enroll-sms flow applies, so a seeded number is stored in the
        // shape auth-sms/auth-sms-lookup later compare against.
        val normalized = phoneNumber.replace("\\s+".toRegex(), "").trim()
        val enrollment = enrollmentRepository.save(AuthSmsEnrollment(phoneNumber = normalized))
        return EnrollmentRef(type = SMS_ENROLLMENT_TYPE, id = enrollment.id.toString())
    }
}
