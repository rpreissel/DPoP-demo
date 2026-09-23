package com.example.dpop.auth_kobil.internal.authkobil

import com.example.dpop.kobil_mock.KobilOtpVerification
import com.example.dpop.kobil_mock.KobilRisk
import com.example.dpop.tool_api.UserVerification
import com.example.dpop.auth_kobil.api.v1.KobilOtpStep
import com.example.dpop.auth_kobil.api.v1.KobilUnlockStep
import com.example.dpop.tool_spi.StepData

/**
 * The two steps of auth-kobil. `unlock` is where the client proves it may have the PIN; `otp` is
 * where it returns the reference KOBIL gave it in exchange.
 *
 * No `stepData` holds the PIN: this state is rebuilt on every read
 * (`ToolControllerSupport.buildReadResponse`), so anything living here comes back out again. The
 * PIN therefore only ever appears in the one response to the release call.
 */
internal sealed interface AuthKobilState {
    val step: String

    fun describe(): Pair<String, StepData>

    /**
     * [options] are the ways this particular credential can actually be unlocked - biometrics only
     * if its owner consented (there is a stored secret then), the password only if the account
     * still holds one. Offering a means that does not exist would be a dead control whose only
     * possible outcome is a failed attempt against the login throttle.
     *
     * That this tells the caller whether the account has a password is accepted: `auth-kobil` only
     * ever runs for a caller whose key already matches an enrolled credential of this very
     * account (`ToolDescriptor.keyBinding`), and the same caller is shown `activeMethods` the
     * moment it finishes.
     *
     * The KOBIL user id is named for the app's own lookup of its locally stored secret - it
     * addresses the app's own credential, which is useless without that secret.
     */
    data class Unlock(
        val tenantId: String,
        val kobilUserId: String,
        val options: List<String>,
    ) : AuthKobilState {
        override val step get() = "unlock"

        override fun describe(): Pair<String, StepData> =
            step to KobilUnlockStep(options, tenantId, kobilUserId)
    }

    data class AwaitingOtp(val tenantId: String, val kobilUserId: String) : AuthKobilState {
        override val step get() = "otp"

        override fun describe(): Pair<String, StepData> =
            step to KobilOtpStep(listOf("otp"), tenantId, kobilUserId)
    }
}

internal sealed interface AuthKobilDecision {
    /** Identifier matched and nothing dangerous was reported. */
    data class Complete(val userVerification: UserVerification) : AuthKobilDecision

    /** No live PIN release - never unlocked, or the window closed. */
    data object NotReleased : AuthKobilDecision

    /** KOBIL does not know this one-time password, or it was already spent. */
    data object OtpInvalid : AuthKobilDecision

    /** The assertion came from a device other than the enrolled one. */
    data object WrongDevice : AuthKobilDecision

    /** The device reported something in the blocking set. */
    data class RiskRejected(val risks: Set<KobilRisk>) : AuthKobilDecision
}

internal object AuthKobilFlow {

    /**
     * @param verification what KOBIL returned for the redeemed OTP, or null when it returned
     * nothing - unknown, spent and foreign are one answer there on purpose.
     * @param release the access means of a still-valid PIN release, null when there is none.
     * @param blockingRisks the signals this deployment refuses to authenticate through. A closed
     * enum on both sides, so an unrecognized signal is not a case to guard against - it cannot be
     * constructed.
     */
    fun decide(
        verification: KobilOtpVerification?,
        enrolledDeviceId: String,
        release: UserVerification?,
        blockingRisks: Set<KobilRisk>,
    ): AuthKobilDecision {
        if (release == null) return AuthKobilDecision.NotReleased
        if (verification == null) return AuthKobilDecision.OtpInvalid
        if (verification.deviceId != enrolledDeviceId) return AuthKobilDecision.WrongDevice

        val dangerous = verification.risks intersect blockingRisks
        if (dangerous.isNotEmpty()) return AuthKobilDecision.RiskRejected(dangerous)

        return AuthKobilDecision.Complete(release)
    }
}
