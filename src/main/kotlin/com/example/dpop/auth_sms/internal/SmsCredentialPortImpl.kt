package com.example.dpop.auth_sms.internal

import com.example.dpop.tool_api.PhoneNumber
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
        // The same rule enroll-sms applies (PhoneNumber), so a seeded number is stored in the shape
        // auth-sms/auth-sms-lookup later compare against.
        val enrollment = enrollmentRepository.save(AuthSmsEnrollment(phoneNumber = PhoneNumber.of(phoneNumber).value))
        return EnrollmentRef(type = SMS_ENROLLMENT_TYPE, id = enrollment.id.toString())
    }
}
