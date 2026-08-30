package com.example.dpop.auth_password.internal.enrollpassword

import com.example.dpop.auth_password.DEMO_PASSWORD
import com.example.dpop.tool_spi.demoData

/**
 * Single-shot flow (docs/03-tool-architektur.md #3, the optional Flow pattern): a chosen password
 * is self-verifying, so there is no confirmation step and thus no persisted partial state -
 * [decide] works from the input alone.
 */
internal data class EnrollPasswordInput(val password: String? = null)

/** What [EnrollPasswordFlow.decide] concluded should happen. */
internal sealed interface EnrollPasswordDecision {
    data class Enroll(val password: String) : EnrollPasswordDecision
    data class TooShort(val raw: String) : EnrollPasswordDecision
    data object Unchanged : EnrollPasswordDecision
}

internal object EnrollPasswordFlow {

    fun decide(input: EnrollPasswordInput): EnrollPasswordDecision {
        val value = input.password ?: return EnrollPasswordDecision.Unchanged
        return if (value.length < MIN_PASSWORD_LENGTH) {
            EnrollPasswordDecision.TooShort(value)
        } else {
            EnrollPasswordDecision.Enroll(value)
        }
    }

    /** Same derivation for start/patch/read - one place turns the state into `next.step`/`stepData`. */
    fun describe(): Pair<String, Map<String, Any?>> =
        "enroll" to mapOf("missingFields" to listOf("password"), demoData("password" to DEMO_PASSWORD))

    const val MIN_PASSWORD_LENGTH = 8
}
