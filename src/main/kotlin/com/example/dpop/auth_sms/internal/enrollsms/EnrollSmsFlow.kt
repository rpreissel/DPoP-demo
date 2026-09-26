package com.example.dpop.auth_sms.internal.enrollsms

import com.example.dpop.auth_sms.internal.TanGenerator
import java.time.Instant
import java.util.UUID
import com.example.dpop.tool_spi.MissingFields
import com.example.dpop.tool_spi.StepData

private const val STEP_ENROLL = "enroll"
private const val STEP_TAN_INPUT = "tanInput"
private const val FIELD_PHONE_NUMBER = "phoneNumber"
private const val FIELD_TAN = "tan"

/**
 * Pure state of the enroll-sms flow (docs/03-tool-architektur.md #3, the optional Flow pattern) -
 * never leaves this file. Persisted as [EnrollSmsToolSession]'s nullable columns; [Companion.of] is
 * the one place that turns those columns back into this type.
 */
internal sealed interface EnrollSmsState {
    /** `next.step` for this position. */
    val step: String
    val missingFields: List<String>

    /** Same derivation for start/patch/read - one place turns this state into `next.step`/`stepData`. */
    fun describe(): Pair<String, StepData> = step to MissingFields(missingFields)

    data object AwaitingPhoneNumber : EnrollSmsState {
        override val step = STEP_ENROLL
        override val missingFields = listOf(FIELD_PHONE_NUMBER)
    }

    data class AwaitingTan(val phoneNumber: String, val issuedTanHash: String, val tanExpiresAt: Instant) : EnrollSmsState {
        override val step = STEP_TAN_INPUT
        override val missingFields = listOf(FIELD_TAN)
    }

    companion object {
        /** Turns [EnrollSmsToolSession]'s persisted, nullable columns back into a [EnrollSmsState]. */
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
            return if (isAllowed(normalized)) {
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

    /** Separators out, a leading `00` becomes `+` - "+49 170 123 45-67", "0049 (170) 1234567" are the same number. */
    private fun normalize(phoneNumber: String) =
        phoneNumber.replace(SEPARATORS, "").let { if (it.startsWith("00")) "+" + it.removePrefix("00") else it }

    /**
     * International format with a country code from the EU/EEA (review 2026-09, Phase F): SMS to
     * premium destinations is the classic abuse of a public "send me a code" endpoint ("SMS
     * pumping"), and nobody registers here with a number from elsewhere. At most 15 digits (E.164).
     */
    private fun isAllowed(number: String): Boolean {
        if (!E164.matches(number)) return false
        val digits = number.removePrefix("+")
        return ALLOWED_COUNTRY_CODES.any { digits.startsWith(it) && digits.length > it.length + 5 }
    }

    private val SEPARATORS = "[\\s\\-/().]".toRegex()
    private val E164 = "^\\+[1-9][0-9]{7,14}$".toRegex()

    /** EU member states plus the EEA (Iceland, Liechtenstein, Norway). */
    private val ALLOWED_COUNTRY_CODES = setOf(
        "43", "32", "359", "385", "357", "420", "45", "372", "358", "33", "49", "30", "36", "353", "39",
        "371", "370", "352", "356", "31", "48", "351", "40", "421", "386", "34", "46",
        "354", "423", "47",
    )
}
