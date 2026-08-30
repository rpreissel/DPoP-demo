package com.example.dpop.auth_sms.internal.authsmslookup

import com.example.dpop.auth_sms.internal.TanGenerator
import com.example.dpop.tool_spi.DEMO_EMAIL
import com.example.dpop.tool_spi.demoData
import java.time.Instant
import java.util.UUID

private const val STEP_AUTH = "auth"
private const val STEP_TAN_INPUT = "tanInput"
private const val FIELD_EMAIL = "email"
private const val FIELD_TAN = "tan"

/**
 * Pure state of the auth-sms-lookup flow (docs/03-tool-architektur.md #3, the optional Flow
 * pattern) - never leaves this file. Email resolution itself happens at the controller
 * (`AccountDirectory`, `auth_sms` may not depend on `account`) - this only models what the
 * resolution's RESULT means for the flow's own position, not the lookup itself.
 */
internal sealed interface AuthSmsLookupState {
    /** `next.step` for this position. */
    val step: String
    val missingFields: List<String>
    /** Merged into `stepData` alongside [missingFields] - only `AwaitingEmail` has anything here (the demo address). */
    val extraData: Map<String, Any?> get() = emptyMap()

    /** Same derivation for start/patch/read - one place turns this state into `next.step`/`stepData`. */
    fun describe(): Pair<String, Map<String, Any?>> = step to (mapOf("missingFields" to missingFields) + extraData)

    data object AwaitingEmail : AuthSmsLookupState {
        override val step = STEP_AUTH
        override val missingFields = listOf(FIELD_EMAIL)
        override val extraData = mapOf(demoData(FIELD_EMAIL to DEMO_EMAIL))
    }

    /** [accountId] is null when the email never resolved to an active sms method (enumeration protection: still proceeds to AwaitingTan, the TAN check below just always fails). */
    data class AwaitingTan(val accountId: Long?, val issuedTanHash: String, val tanExpiresAt: Instant) : AuthSmsLookupState {
        override val step = STEP_TAN_INPUT
        override val missingFields = listOf(FIELD_TAN)
    }

    companion object {
        /** Turns [AuthSmsLookupToolData]'s persisted, nullable columns back into a [AuthSmsLookupState]. */
        fun of(toolSessionId: UUID, accountId: Long?, issuedTanHash: String?, tanExpiresAt: Instant?): AuthSmsLookupState {
            val hash = issuedTanHash ?: return AwaitingEmail
            return AwaitingTan(
                accountId,
                hash,
                checkNotNull(tanExpiresAt) { "auth-sms-lookup tool data $toolSessionId has issuedTanHash but no tanExpiresAt" }
            )
        }
    }
}

internal sealed interface AuthSmsLookupDecision {
    data class Complete(val accountId: Long) : AuthSmsLookupDecision
    data class WrongTan(val accountId: Long?) : AuthSmsLookupDecision
    /** Nothing usable for the current state - describe it unchanged (start/read, an empty PATCH, or a tan submitted before any email). */
    data class Unchanged(val state: AuthSmsLookupState) : AuthSmsLookupDecision
}

internal object AuthSmsLookupFlow {

    fun decideTan(state: AuthSmsLookupState, tan: String?, tanGenerator: TanGenerator): AuthSmsLookupDecision {
        if (state !is AuthSmsLookupState.AwaitingTan) return AuthSmsLookupDecision.Unchanged(state)
        val value = tan ?: return AuthSmsLookupDecision.Unchanged(state)
        return if (state.accountId != null && tanGenerator.matches(value, state.issuedTanHash, state.tanExpiresAt)) {
            AuthSmsLookupDecision.Complete(state.accountId)
        } else {
            AuthSmsLookupDecision.WrongTan(state.accountId)
        }
    }

}
