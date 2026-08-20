package com.example.dpop.orchestrator.api.v1.authentication.sms

import com.example.dpop.auth_sms.EnrollmentRef
import com.fasterxml.jackson.annotation.JsonInclude

@JsonInclude(JsonInclude.Include.NON_NULL)
data class SmsEnrollPending(
    val phoneNumber: String?,
    val enrollmentRef: EnrollmentRef?,
    val tan: String?,
    val tanVerified: Boolean,
    val enrollmentConfirmed: Boolean
) {

    fun merge(patch: Map<String, Any>?): SmsEnrollPending {
        if (patch == null) return this
        return copy(
            phoneNumber = patch["phoneNumber"] as? String ?: phoneNumber,
            tan = patch["tan"] as? String ?: tan
        )
    }

    fun withEnrollmentRef(ref: EnrollmentRef?): SmsEnrollPending =
        copy(enrollmentRef = ref, tanVerified = false, enrollmentConfirmed = false)

    fun withTanVerified(): SmsEnrollPending =
        copy(tanVerified = true, enrollmentConfirmed = false)

    fun withEnrollmentConfirmed(): SmsEnrollPending =
        copy(tanVerified = true, enrollmentConfirmed = true)

    fun missingUserInputs(): List<String> = buildList {
        if (phoneNumber.isNullOrBlank()) add("phoneNumber")
        if (tan.isNullOrBlank()) add("tan")
    }

    companion object {
        fun empty(): SmsEnrollPending = SmsEnrollPending(null, null, null, false, false)
    }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class SmsUsePending(
    val enrollmentRef: EnrollmentRef,
    val tan: String?
) {

    fun merge(patch: Map<String, Any>?): SmsUsePending {
        if (patch == null) return this
        val newTan = patch["tan"] as? String ?: tan
        return copy(tan = newTan)
    }

    fun missingUserInputs(): List<String> =
        if (tan.isNullOrBlank()) listOf("tan") else emptyList()
}

sealed interface EnrollStep {
    data class NeedInput(val missingFields: List<String>) : EnrollStep
    data class StartEnrollment(val phoneNumber: String) : EnrollStep
    data class ConfirmEnrollment(val ref: EnrollmentRef, val tan: String) : EnrollStep
    data class ActivateMethod(val ref: EnrollmentRef) : EnrollStep
}

sealed interface UseStep {
    data class NeedInput(val missingFields: List<String>) : UseStep
    data class VerifyChallenge(val ref: EnrollmentRef, val tan: String) : UseStep
}
