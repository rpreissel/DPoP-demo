package com.example.dpop.auth_password.internal.authpassword

import com.example.dpop.auth_password.DEMO_PASSWORD
import com.example.dpop.tool_spi.MissingFields
import com.example.dpop.tool_spi.StepData

/** What one PATCH submitted. */
internal data class AuthPasswordInput(val password: String? = null)

/**
 * What [AuthPasswordFlow.decide] concluded should happen. The actual hash comparison isn't
 * decided here - it needs the enrollment row (`AuthPasswordEnrollmentRepository`), an impure
 * lookup this pure function can't do - so [Check] only names the submitted value for the handler
 * to verify.
 */
internal sealed interface AuthPasswordDecision {
    data class Check(val password: String) : AuthPasswordDecision
    /** Nothing usable was submitted - describe the (unique) state unchanged. */
    data object Unchanged : AuthPasswordDecision
}

internal object AuthPasswordFlow {

    fun decide(input: AuthPasswordInput): AuthPasswordDecision =
        input.password?.let { AuthPasswordDecision.Check(it) } ?: AuthPasswordDecision.Unchanged

    /** Same derivation for start/patch/read - one place turns the state into `next.step`/`stepData`. */
    fun describe(): Pair<String, StepData> = "auth" to MissingFields(listOf("password"))

    /** The fixed demo password, so a tester never has to remember one - never part of the step. */
    fun demo(): Map<String, Any?> = mapOf("password" to DEMO_PASSWORD)
}
