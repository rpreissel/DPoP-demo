package com.example.dpop.auth_password.internal.authpasswordlookup

import com.example.dpop.auth_password.DEMO_PASSWORD
import com.example.dpop.tool_spi.DEMO_EMAIL
import com.example.dpop.tool_spi.demoData

/**
 * Single-shot flow (docs/03-tool-architektur.md #3, the optional Flow pattern): email+password
 * arrive and get checked together, self-verifying like `AuthPasswordUseFlow` - no persisted
 * partial state, so [decide] works from the input alone.
 */
internal data class AuthPasswordLookupInput(val email: String? = null, val password: String? = null)

/** What [AuthPasswordLookupFlow.decide] concluded should happen. */
internal sealed interface AuthPasswordLookupDecision {
    data class Check(val email: String, val password: String) : AuthPasswordLookupDecision
    data class Incomplete(val missingFields: List<String>) : AuthPasswordLookupDecision
}

internal object AuthPasswordLookupFlow {

    fun decide(input: AuthPasswordLookupInput): AuthPasswordLookupDecision {
        val missing = buildList {
            if (input.email.isNullOrBlank()) add("email")
            if (input.password.isNullOrBlank()) add("password")
        }
        return if (missing.isEmpty()) {
            AuthPasswordLookupDecision.Check(input.email!!, input.password!!)
        } else {
            AuthPasswordLookupDecision.Incomplete(missing)
        }
    }

    /** Same derivation for start/patch/read - one place turns missing fields into `next.step`/`stepData`. */
    fun describe(missingFields: List<String> = listOf("email", "password")): Pair<String, Map<String, Any?>> =
        "auth" to mapOf("missingFields" to missingFields, demoData("email" to DEMO_EMAIL, "password" to DEMO_PASSWORD))
}
