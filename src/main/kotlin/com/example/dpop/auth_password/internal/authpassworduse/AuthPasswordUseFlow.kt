package com.example.dpop.auth_password.internal.authpassworduse

import com.example.dpop.auth_password.DEMO_PASSWORD
import com.example.dpop.tool_spi.MissingFields
import com.example.dpop.tool_spi.StepData

/** What one PATCH submitted. */
internal data class AuthPasswordUseInput(val password: String? = null)

/**
 * What [AuthPasswordUseFlow.decide] concluded should happen. The actual hash comparison isn't
 * decided here - it needs the enrollment row (`AuthPasswordEnrollmentRepository`), an impure
 * lookup this pure function can't do - so [Check] only names the submitted value for the handler
 * to verify.
 */
internal sealed interface AuthPasswordUseDecision {
    data class Check(val password: String) : AuthPasswordUseDecision
    /** Nothing usable was submitted - describe the (unique) state unchanged. */
    data object Unchanged : AuthPasswordUseDecision
}

internal object AuthPasswordUseFlow {

    fun decide(input: AuthPasswordUseInput): AuthPasswordUseDecision =
        input.password?.let { AuthPasswordUseDecision.Check(it) } ?: AuthPasswordUseDecision.Unchanged

    /** Same derivation for start/patch/read - one place turns the state into `next.step`/`stepData`. */
    fun describe(): Pair<String, StepData> = "auth" to MissingFields(listOf("password"))

    /** The fixed demo password, so a tester never has to remember one - never part of the step. */
    fun demo(): Map<String, Any?> = mapOf("password" to DEMO_PASSWORD)
}
