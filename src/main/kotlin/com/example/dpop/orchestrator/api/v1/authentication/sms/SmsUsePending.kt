package com.example.dpop.orchestrator.api.v1.authentication.sms

import com.example.dpop.auth_sms.EnrollmentRef

@JvmRecord
data class SmsUsePending(
    val enrollmentRef: EnrollmentRef?,
    val tan: String?
) {

    fun merge(patch: Map<String, Any>?): SmsUsePending {
        if (patch == null) return this
        val newTan = if (patch.containsKey("tan")) patch["tan"] as String? else tan
        return SmsUsePending(enrollmentRef, newTan)
    }

    fun missingUserInputs(): List<String> =
        if (tan.isNullOrBlank()) listOf("tan") else emptyList()
}
