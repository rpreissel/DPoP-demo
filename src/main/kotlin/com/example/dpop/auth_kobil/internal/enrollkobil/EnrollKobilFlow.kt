package com.example.dpop.auth_kobil.internal.enrollkobil

import com.example.dpop.tool_api.UserVerification

/**
 * What the client still owes at the `activate` step (docs/03-tool-architektur.md #3, the Flow
 * pattern). Everything the client *needs* for that step - tenant, user id, activation code, PIN,
 * unlock secret - was handed out when the tool started; what comes back is only the confirmation
 * that the SDK ran and which access means now guards the local secret.
 */
internal data object EnrollKobilState {
    val step: String get() = "activate"

    fun describe(): Pair<String, Map<String, Any?>?> = step to mapOf("missingFields" to MISSING_FIELDS)

    private val MISSING_FIELDS = listOf("activated", "biometricConsent")
}

internal sealed interface EnrollKobilDecision {
    /**
     * The SDK reported back and KOBIL knows a device for this user: the credential can be written.
     *
     * [biometricConsent] decides whether the local unlock secret gets a server-side counterpart at
     * all. Without consent none is stored, and the credential simply has one way in - the account
     * password it depends on anyway. That is the whole consent mechanism: no flag to honour later,
     * just a secret that does or does not exist.
     */
    data class Enroll(val deviceId: String, val biometricConsent: Boolean) : EnrollKobilDecision {
        /** What the run reports as its access means - derived from the consent, never a second input. */
        val userVerification: UserVerification
            get() = if (biometricConsent) UserVerification.BIOMETRIC else UserVerification.PIN
    }

    /**
     * Nothing to decide yet - the client has not run the SDK, or KOBIL has no device for this
     * user. Deliberately NOT a `Failed`: no attempt was made and nothing was guessed, so charging
     * the journey's attempt budget for it would punish a page reload.
     */
    data object Unchanged : EnrollKobilDecision

}

internal object EnrollKobilFlow {

    fun decide(activated: Boolean?, biometricConsent: Boolean?, deviceId: String?): EnrollKobilDecision {
        if (activated != true || biometricConsent == null || deviceId == null) return EnrollKobilDecision.Unchanged
        return EnrollKobilDecision.Enroll(deviceId, biometricConsent)
    }
}
