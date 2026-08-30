package com.example.dpop.auth_email.internal.enrollemail

import com.example.dpop.auth_email.internal.EmailCodeGenerator
import com.example.dpop.tool_spi.DEMO_EMAIL
import com.example.dpop.tool_spi.demoData
import java.time.Instant
import java.util.UUID

private const val STEP_ENROLL = "enroll"
private const val STEP_CODE_INPUT = "codeInput"
private const val FIELD_EMAIL = "email"
private const val FIELD_CODE = "code"

/**
 * Pure state of the enroll-email flow (docs/03-tool-architektur.md #3, the optional Flow
 * pattern) - never leaves this file. Mirrors `auth_sms`'s `EnrollSmsFlow`.
 */
internal sealed interface EnrollEmailState {
    /** `next.step` for this position. */
    val step: String
    val missingFields: List<String>
    /** Merged into `stepData` alongside [missingFields] - only `AwaitingEmail` has anything here (the demo address). */
    val extraData: Map<String, Any?> get() = emptyMap()

    /** Same derivation for start/patch/read - one place turns this state into `next.step`/`stepData`. */
    fun describe(): Pair<String, Map<String, Any?>> = step to (mapOf("missingFields" to missingFields) + extraData)

    data object AwaitingEmail : EnrollEmailState {
        override val step = STEP_ENROLL
        override val missingFields = listOf(FIELD_EMAIL)
        override val extraData = mapOf(demoData(FIELD_EMAIL to DEMO_EMAIL))
    }

    data class AwaitingCode(val email: String, val issuedCodeHash: String, val codeExpiresAt: Instant) : EnrollEmailState {
        override val step = STEP_CODE_INPUT
        override val missingFields = listOf(FIELD_CODE)
    }

    companion object {
        /** Turns [EnrollEmailToolData]'s persisted, nullable columns back into a [EnrollEmailState]. */
        fun of(toolSessionId: UUID, email: String?, issuedCodeHash: String?, codeExpiresAt: Instant?): EnrollEmailState {
            val value = email ?: return AwaitingEmail
            return AwaitingCode(
                value,
                checkNotNull(issuedCodeHash) { "enroll-email tool data $toolSessionId has an email but no issuedCodeHash" },
                checkNotNull(codeExpiresAt) { "enroll-email tool data $toolSessionId has an email but no codeExpiresAt" }
            )
        }
    }
}

/** What one PATCH submitted - both optional, exactly the API's "only the changed part" rule. */
internal data class EnrollEmailInput(val email: String? = null, val code: String? = null)

/** What [EnrollEmailFlow.decide] concluded should happen. */
internal sealed interface EnrollEmailDecision {
    /**
     * A well-formatted email was submitted - always wins over a [EnrollEmailInput.code]
     * submitted in the same call, in every state: a changed (or first) address invalidates
     * whatever code was pending for a different one. Uniqueness (is this address already taken)
     * is a DB lookup, deliberately not decided here - the handler checks it before acting on this.
     */
    data class RequestCode(val email: String) : EnrollEmailDecision
    data class InvalidEmail(val raw: String) : EnrollEmailDecision
    data class Complete(val email: String) : EnrollEmailDecision
    data class WrongCode(val state: EnrollEmailState.AwaitingCode) : EnrollEmailDecision
    /** Nothing usable for the current state - describe it unchanged (start/read, or an empty PATCH). */
    data class Unchanged(val state: EnrollEmailState) : EnrollEmailDecision
}

internal object EnrollEmailFlow {

    fun decide(state: EnrollEmailState, input: EnrollEmailInput, emailCodeGenerator: EmailCodeGenerator): EnrollEmailDecision {
        input.email?.let { raw ->
            val normalized = raw.trim().lowercase()
            return if (EMAIL_PATTERN.matches(normalized)) {
                EnrollEmailDecision.RequestCode(normalized)
            } else {
                EnrollEmailDecision.InvalidEmail(raw)
            }
        }
        return when (state) {
            is EnrollEmailState.AwaitingEmail -> EnrollEmailDecision.Unchanged(state)
            is EnrollEmailState.AwaitingCode -> {
                val code = input.code ?: return EnrollEmailDecision.Unchanged(state)
                if (emailCodeGenerator.matches(code, state.issuedCodeHash, state.codeExpiresAt)) {
                    EnrollEmailDecision.Complete(state.email)
                } else {
                    EnrollEmailDecision.WrongCode(state)
                }
            }
        }
    }

    private val EMAIL_PATTERN = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$".toRegex()
}
