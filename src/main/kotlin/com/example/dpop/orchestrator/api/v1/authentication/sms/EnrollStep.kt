package com.example.dpop.orchestrator.api.v1.authentication.sms

import com.example.dpop.auth_sms.EnrollmentRef

sealed interface EnrollStep {
    @JvmRecord
    data class NeedInput(val missingFields: List<String>) : EnrollStep

    @JvmRecord
    data class StartEnrollment(val phoneNumber: String?) : EnrollStep

    @JvmRecord
    data class ConfirmEnrollment(val ref: EnrollmentRef, val tan: String?) : EnrollStep

    @JvmRecord
    data class ActivateMethod(val ref: EnrollmentRef) : EnrollStep
}
