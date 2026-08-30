package com.example.dpop.auth_sms.internal.enrollsms

import com.example.dpop.auth_sms.internal.TanGenerator
import java.time.Instant
import java.util.UUID

private const val STEP_ENROLL = "enroll"
private const val STEP_TAN_INPUT = "tanInput"
private const val FIELD_PHONE_NUMBER = "phoneNumber"
private const val FIELD_TAN = "tan"

/**
 * Pure state of the enroll-sms flow (docs/03-tool-architektur.md #3, the optional Flow pattern) -
 * never leaves this file. Persisted as [EnrollSmsToolData]'s nullable columns; [Companion.of] is
 * the one place that turns those columns back into this type.
 */
internal sealed interface EnrollSmsState {
    /** `next.step` for this position. */
    val step: String
    val missingFields: List<String>

    /** Same derivation for start/patch/read - one place turns this state into `next.step`/`stepData`. */
    fun describe(): Pair<String, Map<String, Any?>> = step to mapOf("missingFields" to missingFields)

    data object AwaitingPhoneNumber : EnrollSmsState {
        override val step = STEP_ENROLL
        override val missingFields = listOf(FIELD_PHONE_NUMBER)
    }

    data class AwaitingTan(val phoneNumber: String, val issuedTanHash: String, val tanExpiresAt: Instant) : EnrollSmsState {
        override val step = STEP_TAN_INPUT
        override val missingFields = listOf(FIELD_TAN)
    }

    companion object {
        /** Turns [EnrollSmsToolData]'s persisted, nullable columns back into a [EnrollSmsState]. */
        fun of(toolSessionId: UUID, phoneNumber: String?, issuedTanHash: String?, tanExpiresAt: Instant?): EnrollSmsState {
            val number = phoneNumber ?: return AwaitingPhoneNumber
            return AwaitingTan(
                number,
                checkNotNull(issuedTanHash) { "enroll-sms tool data $toolSessionId has a phoneNumber but no issuedTanHash" },
                checkNotNull(tanExpiresAt) { "enroll-sms tool data $toolSessionId has a phoneNumber but no tanExpiresAt" }
            )
        }
    }
}

/** What one PATCH submitted - both optional, exactly the API's "only the changed part" rule. */
internal data class EnrollSmsInput(val phoneNumber: String? = null, val tan: String? = null)

/** What [EnrollSmsFlow.decide] concluded should happen. */
internal sealed interface EnrollSmsDecision {
    /**
     * A phone number was submitted - always wins over a [EnrollSmsInput.tan] submitted in the
     * same call, in every state: a changed (or first) number invalidates whatever TAN was
     * pending for a different one, so there is nothing left to check it against.
     */
    data class SendTan(val phoneNumber: String) : EnrollSmsDecision
    data class InvalidPhoneNumber(val raw: String) : EnrollSmsDecision
    data class Complete(val phoneNumber: String) : EnrollSmsDecision
    data class WrongTan(val state: EnrollSmsState.AwaitingTan) : EnrollSmsDecision
    /** Nothing usable for the current state - describe it unchanged (start/read, or an empty PATCH). */
    data class Unchanged(val state: EnrollSmsState) : EnrollSmsDecision
}

/**
 * The one place that decides what an enroll-sms step means, instead of three handler methods
 * each re-deriving it from which parameter happens to be non-null.
 */
internal object EnrollSmsFlow {

    fun decide(state: EnrollSmsState, input: EnrollSmsInput, tanGenerator: TanGenerator): EnrollSmsDecision {
        input.phoneNumber?.let { raw ->
            val normalized = normalize(raw)
            return if (PHONE_PATTERN.matches(normalized)) {
                EnrollSmsDecision.SendTan(normalized)
            } else {
                EnrollSmsDecision.InvalidPhoneNumber(raw)
            }
        }
        return when (state) {
            is EnrollSmsState.AwaitingPhoneNumber -> EnrollSmsDecision.Unchanged(state)
            is EnrollSmsState.AwaitingTan -> {
                val tan = input.tan ?: return EnrollSmsDecision.Unchanged(state)
                if (tanGenerator.matches(tan, state.issuedTanHash, state.tanExpiresAt)) {
                    EnrollSmsDecision.Complete(state.phoneNumber)
                } else {
                    EnrollSmsDecision.WrongTan(state)
                }
            }
        }
    }

    private fun normalize(phoneNumber: String) = phoneNumber.replace("\\s+".toRegex(), "").trim()

    private val PHONE_PATTERN = "^\\+?[0-9]{6,20}$".toRegex()
}
