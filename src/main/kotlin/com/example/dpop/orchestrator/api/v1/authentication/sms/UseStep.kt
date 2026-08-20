package com.example.dpop.orchestrator.api.v1.authentication.sms

import com.example.dpop.auth_sms.EnrollmentRef

sealed interface UseStep {
    @JvmRecord
    data class NeedInput(val missingFields: List<String>) : UseStep

    @JvmRecord
    data class VerifyChallenge(val ref: EnrollmentRef, val tan: String?) : UseStep
}
