package com.example.dpop.auth_email.internal.authemaillookup

import com.example.dpop.auth_email.internal.EmailCodeGenerator
import com.example.dpop.tool_spi.DEMO_EMAIL
import com.example.dpop.tool_spi.demoData
import java.time.Instant
import java.util.UUID

private const val STEP_AUTH = "auth"
private const val STEP_CODE_INPUT = "codeInput"
private const val FIELD_EMAIL = "email"
private const val FIELD_CODE = "code"

/**
 * Pure state of the auth-email-lookup flow (docs/03-tool-architektur.md #3, the optional Flow
 * pattern) - never leaves this file. Mirrors `auth_sms`'s `AuthSmsLookupFlow`.
 */
internal sealed interface AuthEmailLookupState {
    /** `next.step` for this position. */
    val step: String
    val missingFields: List<String>
    /** Merged into `stepData` alongside [missingFields] - only `AwaitingEmail` has anything here (the demo address). */
    val extraData: Map<String, Any?> get() = emptyMap()

    /** Same derivation for start/patch/read - one place turns this state into `next.step`/`stepData`. */
    fun describe(): Pair<String, Map<String, Any?>> = step to (mapOf("missingFields" to missingFields) + extraData)

    data object AwaitingEmail : AuthEmailLookupState {
        override val step = STEP_AUTH
        override val missingFields = listOf(FIELD_EMAIL)
        override val extraData = mapOf(demoData(FIELD_EMAIL to DEMO_EMAIL))
    }

    /** [accountId] is null when the email never resolved to a confirmed address (enumeration protection: still proceeds to AwaitingCode, the code check below just always fails). */
    data class AwaitingCode(val accountId: Long?, val issuedCodeHash: String, val codeExpiresAt: Instant) : AuthEmailLookupState {
        override val step = STEP_CODE_INPUT
        override val missingFields = listOf(FIELD_CODE)
    }

    companion object {
        /** Turns [AuthEmailLookupToolData]'s persisted, nullable columns back into a [AuthEmailLookupState]. */
        fun of(toolSessionId: UUID, accountId: Long?, issuedCodeHash: String?, codeExpiresAt: Instant?): AuthEmailLookupState {
            val hash = issuedCodeHash ?: return AwaitingEmail
            return AwaitingCode(
                accountId,
                hash,
                checkNotNull(codeExpiresAt) { "auth-email-lookup tool data $toolSessionId has issuedCodeHash but no codeExpiresAt" }
            )
        }
    }
}

internal sealed interface AuthEmailLookupDecision {
    data class Complete(val accountId: Long) : AuthEmailLookupDecision
    data class WrongCode(val accountId: Long?) : AuthEmailLookupDecision
    /** Nothing usable for the current state - describe it unchanged (start/read, an empty PATCH, or a code submitted before any email). */
    data class Unchanged(val state: AuthEmailLookupState) : AuthEmailLookupDecision
}

internal object AuthEmailLookupFlow {

    fun decideCode(state: AuthEmailLookupState, code: String?, emailCodeGenerator: EmailCodeGenerator): AuthEmailLookupDecision {
        if (state !is AuthEmailLookupState.AwaitingCode) return AuthEmailLookupDecision.Unchanged(state)
        val value = code ?: return AuthEmailLookupDecision.Unchanged(state)
        return if (state.accountId != null && emailCodeGenerator.matches(value, state.issuedCodeHash, state.codeExpiresAt)) {
            AuthEmailLookupDecision.Complete(state.accountId)
        } else {
            AuthEmailLookupDecision.WrongCode(state.accountId)
        }
    }

}
