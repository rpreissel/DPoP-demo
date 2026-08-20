package com.example.dpop.auth_sms

data class EnrollmentRef(val id: Long?)

data class AuthSmsEnrollResult(
    val enrollmentRef: EnrollmentRef,
    val tan: String
)

data class AuthSmsChallengeResult(
    val enrollmentRef: EnrollmentRef,
    val tan: String
)
