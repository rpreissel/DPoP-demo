package com.example.dpop.auth_sms

@JvmRecord
data class AuthSmsEnrollResult(
    val enrollmentRef: EnrollmentRef,
    val tan: String
)
