package com.example.dpop.auth_email.internal.authemailuse

import com.example.dpop.auth_email.internal.EmailCodeGenerator
import java.time.Instant
import java.util.UUID

private const val STEP_AUTH = "auth"
private const val FIELD_CODE = "code"

/**
 * Pure state of the auth-email flow (docs/03-tool-architektur.md #3, the optional Flow pattern) -
 * never leaves this file. Mirrors `auth_sms`'s `AuthSmsUseFlow`: the account's confirmed address
 * is resolved once at `start`, so there is only ever this one shape.
 */
internal data class AuthEmailUseState(val issuedCodeHash: String, val codeExpiresAt: Instant) {
    val step: String get() = STEP_AUTH
    val missingFields: List<String> get() = listOf(FIELD_CODE)

    /** Same derivation for start/patch/read - one place turns this state into `next.step`/`stepData`. */
    fun describe(): Pair<String, Map<String, Any?>> = step to mapOf("missingFields" to missingFields)

    companion object {
        /** Turns [AuthEmailUseToolData]'s persisted columns back into a [AuthEmailUseState]. */
        fun of(toolSessionId: UUID, issuedCodeHash: String?, codeExpiresAt: Instant?): AuthEmailUseState = AuthEmailUseState(
            checkNotNull(issuedCodeHash) { "auth-email tool data $toolSessionId without issuedCodeHash" },
            checkNotNull(codeExpiresAt) { "auth-email tool data $toolSessionId without codeExpiresAt" }
        )
    }
}

/** What one PATCH submitted. */
internal data class AuthEmailUseInput(val code: String? = null)

/** What [AuthEmailUseFlow.decide] concluded should happen. */
internal sealed interface AuthEmailUseDecision {
    data object Complete : AuthEmailUseDecision
    data object WrongCode : AuthEmailUseDecision
    /** Nothing usable was submitted - describe the (unique) state unchanged. */
    data object Unchanged : AuthEmailUseDecision
}

internal object AuthEmailUseFlow {

    fun decide(state: AuthEmailUseState, input: AuthEmailUseInput, emailCodeGenerator: EmailCodeGenerator): AuthEmailUseDecision {
        val code = input.code ?: return AuthEmailUseDecision.Unchanged
        return if (emailCodeGenerator.matches(code, state.issuedCodeHash, state.codeExpiresAt)) {
            AuthEmailUseDecision.Complete
        } else {
            AuthEmailUseDecision.WrongCode
        }
    }

}
