package com.example.dpop.orchestrator.api.v1.authentication.sms

import com.example.dpop.auth_sms.EnrollmentRef

@JvmRecord
data class SmsEnrollPending(
    val phoneNumber: String?,
    val enrollmentRef: EnrollmentRef?,
    val tan: String?,
    val tanVerified: Boolean,
    val enrollmentConfirmed: Boolean
) {

    fun merge(patch: Map<String, Any>?): SmsEnrollPending {
        if (patch == null) return this
        val phone = if (patch.containsKey("phoneNumber")) patch["phoneNumber"] as String? else phoneNumber
        val newTan = if (patch.containsKey("tan")) patch["tan"] as String? else tan
        return SmsEnrollPending(phone, enrollmentRef, newTan, tanVerified, enrollmentConfirmed)
    }

    fun withEnrollmentRef(ref: EnrollmentRef?): SmsEnrollPending =
        SmsEnrollPending(phoneNumber, ref, tan, false, false)

    fun withTanVerified(): SmsEnrollPending =
        SmsEnrollPending(phoneNumber, enrollmentRef, tan, true, false)

    fun withEnrollmentConfirmed(): SmsEnrollPending =
        SmsEnrollPending(phoneNumber, enrollmentRef, tan, true, true)

    fun missingUserInputs(): List<String> {
        val missing = mutableListOf<String>()
        if (phoneNumber.isNullOrBlank()) missing.add("phoneNumber")
        if (tan.isNullOrBlank()) missing.add("tan")
        return missing
    }

    companion object {
        @JvmStatic
        fun empty(): SmsEnrollPending = SmsEnrollPending(null, null, null, false, false)
    }
}
