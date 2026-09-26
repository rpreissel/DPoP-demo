package com.example.dpop.auth_email.internal.authemail

import com.example.dpop.auth_email.internal.EmailCodeGenerator
import java.time.Instant
import java.util.UUID
import com.example.dpop.tool_spi.MissingFields
import com.example.dpop.tool_spi.StepData

private const val STEP_AUTH = "auth"
private const val FIELD_CODE = "code"

/**
 * Pure state of the auth-email flow (docs/03-tool-architektur.md #3, the optional Flow pattern) -
 * never leaves this file. Mirrors `auth_sms`'s `AuthSmsFlow`: the account's confirmed address
 * is resolved once at `start`, so there is only ever this one shape.
 */
internal data class AuthEmailState(val issuedCodeHash: String, val codeExpiresAt: Instant) {
    val step: String get() = STEP_AUTH
    val missingFields: List<String> get() = listOf(FIELD_CODE)

    /** Same derivation for start/patch/read - one place turns this state into `next.step`/`stepData`. */
    fun describe(): Pair<String, StepData> = step to MissingFields(missingFields)

    companion object {
        /** Turns [AuthEmailToolSession]'s persisted columns back into a [AuthEmailState]. */
        fun of(toolSessionId: UUID, issuedCodeHash: String?, codeExpiresAt: Instant?): AuthEmailState = AuthEmailState(
            checkNotNull(issuedCodeHash) { "auth-email tool data $toolSessionId without issuedCodeHash" },
            checkNotNull(codeExpiresAt) { "auth-email tool data $toolSessionId without codeExpiresAt" }
        )
    }
}

/** What one PATCH submitted. */
internal data class AuthEmailInput(val code: String? = null)

/** What [AuthEmailFlow.decide] concluded should happen. */
internal sealed interface AuthEmailDecision {
    data object Complete : AuthEmailDecision
    data object WrongCode : AuthEmailDecision
    /** Nothing usable was submitted - describe the (unique) state unchanged. */
    data object Unchanged : AuthEmailDecision
}

internal object AuthEmailFlow {

    fun decide(state: AuthEmailState, input: AuthEmailInput, emailCodeGenerator: EmailCodeGenerator): AuthEmailDecision {
        val code = input.code ?: return AuthEmailDecision.Unchanged
        return if (emailCodeGenerator.matches(code, state.issuedCodeHash, state.codeExpiresAt)) {
            AuthEmailDecision.Complete
        } else {
            AuthEmailDecision.WrongCode
        }
    }

}
