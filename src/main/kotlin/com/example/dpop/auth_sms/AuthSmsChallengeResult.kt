package com.example.dpop.auth_sms

@JvmRecord
data class AuthSmsChallengeResult(
    val enrollmentRef: EnrollmentRef,
    val tan: String
)
