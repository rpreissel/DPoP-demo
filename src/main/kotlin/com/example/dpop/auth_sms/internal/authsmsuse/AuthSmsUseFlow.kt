package com.example.dpop.auth_sms.internal.authsmsuse

import com.example.dpop.auth_sms.internal.TanGenerator
import java.time.Instant
import java.util.UUID

private const val STEP_AUTH = "auth"
private const val FIELD_TAN = "tan"

/**
 * Pure state of the auth-sms flow (docs/03-tool-architektur.md #3, the optional Flow pattern) -
 * never leaves this file. Single-shape on purpose: the account is already known via the channel
 * (`start`'s `enrollmentRef` resolves it), so there is no earlier phase to model - unlike
 * `EnrollSmsFlow`, this never moves to a different state, only repeats or fails.
 */
internal data class AuthSmsUseState(val issuedTanHash: String, val tanExpiresAt: Instant) {
    val step: String get() = STEP_AUTH
    val missingFields: List<String> get() = listOf(FIELD_TAN)

    /** Same derivation for start/patch/read - one place turns this state into `next.step`/`stepData`. */
    fun describe(): Pair<String, Map<String, Any?>> = step to mapOf("missingFields" to missingFields)

    companion object {
        /** Turns [AuthSmsUseToolData]'s persisted columns back into a [AuthSmsUseState]. */
        fun of(toolSessionId: UUID, issuedTanHash: String?, tanExpiresAt: Instant?): AuthSmsUseState = AuthSmsUseState(
            checkNotNull(issuedTanHash) { "auth-sms tool data $toolSessionId without issuedTanHash" },
            checkNotNull(tanExpiresAt) { "auth-sms tool data $toolSessionId without tanExpiresAt" }
        )
    }
}

/** What one PATCH submitted. */
internal data class AuthSmsUseInput(val tan: String? = null)

/** What [AuthSmsUseFlow.decide] concluded should happen. */
internal sealed interface AuthSmsUseDecision {
    data object Complete : AuthSmsUseDecision
    data object WrongTan : AuthSmsUseDecision
    /** Nothing usable was submitted - describe the (unique) state unchanged. */
    data object Unchanged : AuthSmsUseDecision
}

internal object AuthSmsUseFlow {

    fun decide(state: AuthSmsUseState, input: AuthSmsUseInput, tanGenerator: TanGenerator): AuthSmsUseDecision {
        val tan = input.tan ?: return AuthSmsUseDecision.Unchanged
        return if (tanGenerator.matches(tan, state.issuedTanHash, state.tanExpiresAt)) {
            AuthSmsUseDecision.Complete
        } else {
            AuthSmsUseDecision.WrongTan
        }
    }

}
