package com.example.dpop.auth_email.internal.confirmemail

import com.example.dpop.auth_email.internal.EmailCodeGenerator
import com.example.dpop.tool_api.Email
import com.example.dpop.tool_spi.DEMO_EMAIL
import com.example.dpop.tool_spi.demoData
import java.time.Instant
import java.util.UUID

private const val STEP_INPUT = "input"
private const val STEP_CODE_INPUT = "codeInput"
private const val FIELD_EMAIL = "email"
private const val FIELD_CODE = "code"

/**
 * Pure state of the confirm-email flow (docs/03-tool-architektur.md #3, the optional Flow
 * pattern) - never leaves this file. Mirrors `auth_sms`'s `EnrollSmsFlow`.
 */
internal sealed interface ConfirmEmailState {
    /** `next.step` for this position. */
    val step: String
    val missingFields: List<String>
    /** Merged into `stepData` alongside [missingFields] - only `AwaitingEmail` has anything here (the demo address). */
    val extraData: Map<String, Any?> get() = emptyMap()

    /** Same derivation for start/patch/read - one place turns this state into `next.step`/`stepData`. */
    fun describe(): Pair<String, Map<String, Any?>> = step to (mapOf("missingFields" to missingFields) + extraData)

    data object AwaitingEmail : ConfirmEmailState {
        override val step = STEP_INPUT
        override val missingFields = listOf(FIELD_EMAIL)
        override val extraData = mapOf(demoData(FIELD_EMAIL to DEMO_EMAIL))
    }

    data class AwaitingCode(val email: String, val issuedCodeHash: String, val codeExpiresAt: Instant) : ConfirmEmailState {
        override val step = STEP_CODE_INPUT
        override val missingFields = listOf(FIELD_CODE)
    }

    companion object {
        /** Turns [ConfirmEmailToolSession]'s persisted, nullable columns back into a [ConfirmEmailState]. */
        fun of(toolSessionId: UUID, email: String?, issuedCodeHash: String?, codeExpiresAt: Instant?): ConfirmEmailState {
            val value = email ?: return AwaitingEmail
            return AwaitingCode(
                value,
                checkNotNull(issuedCodeHash) { "confirm-email tool data $toolSessionId has an email but no issuedCodeHash" },
                checkNotNull(codeExpiresAt) { "confirm-email tool data $toolSessionId has an email but no codeExpiresAt" }
            )
        }
    }
}

/** What one PATCH submitted - both optional, exactly the API's "only the changed part" rule. */
internal data class ConfirmEmailInput(val email: String? = null, val code: String? = null)

/** What [ConfirmEmailFlow.decide] concluded should happen. */
internal sealed interface ConfirmEmailDecision {
    /**
     * A well-formatted email was submitted - always wins over a [ConfirmEmailInput.code]
     * submitted in the same call, in every state: a changed (or first) address invalidates
     * whatever code was pending for a different one. Uniqueness (is this address already taken)
     * is a DB lookup, deliberately not decided here - the handler checks it before acting on this.
     */
    data class RequestCode(val email: String) : ConfirmEmailDecision
    data class InvalidEmail(val raw: String) : ConfirmEmailDecision
    data class Complete(val email: String) : ConfirmEmailDecision
    data class WrongCode(val state: ConfirmEmailState.AwaitingCode) : ConfirmEmailDecision
    /** Nothing usable for the current state - describe it unchanged (start/read, or an empty PATCH). */
    data class Unchanged(val state: ConfirmEmailState) : ConfirmEmailDecision
}

internal object ConfirmEmailFlow {

    fun decide(state: ConfirmEmailState, input: ConfirmEmailInput, emailCodeGenerator: EmailCodeGenerator): ConfirmEmailDecision {
        input.email?.let { raw ->
            val email = Email.ofOrNull(raw) ?: return ConfirmEmailDecision.InvalidEmail(raw)
            return ConfirmEmailDecision.RequestCode(email.value)
        }
        return when (state) {
            is ConfirmEmailState.AwaitingEmail -> ConfirmEmailDecision.Unchanged(state)
            is ConfirmEmailState.AwaitingCode -> {
                val code = input.code ?: return ConfirmEmailDecision.Unchanged(state)
                if (emailCodeGenerator.matches(code, state.issuedCodeHash, state.codeExpiresAt)) {
                    ConfirmEmailDecision.Complete(state.email)
                } else {
                    ConfirmEmailDecision.WrongCode(state)
                }
            }
        }
    }
}
