package com.example.dpop.id_fsc.internal

import java.security.MessageDigest

/**
 * Pure state of the ident-fsc flow (docs/03-tool-architektur.md #3, the optional Flow pattern) -
 * never leaves this file. One flat shape, not a sealed hierarchy: unlike a two-field OTP flow,
 * four independently-suppliable fields don't collapse into a small number of named positions -
 * [missingFields] derives what's still needed from whichever combination is present.
 */
internal data class IdentFscState(
    val kvnr: String? = null,
    val name: String? = null,
    val vorname: String? = null,
    val fscHash: String? = null,
    val personId: Long? = null
)

/** What one PATCH submitted - all optional, exactly the API's "only the changed part" rule. */
internal data class IdentFscInput(
    val kvnr: String? = null,
    val name: String? = null,
    val vorname: String? = null,
    val fsc: String? = null,
    val personId: Long? = null
)

/** What [IdentFscFlow.decide] concluded once a state is fully filled in. */
internal sealed interface IdentFscDecision {
    data object Incomplete : IdentFscDecision
    data object PersonNotFound : IdentFscDecision
    data class Verify(val personId: Long, val name: String, val vorname: String, val fscHash: String) : IdentFscDecision
}

/**
 * What [IdentFscFlow.decideVerification] concluded from the lookups [IdentFscDecision.Verify]
 * asked for - a DB-dependent name match and a code lookup, both impure, so this step is
 * deliberately separate: it only combines already-fetched booleans.
 */
internal sealed interface IdentFscVerifyDecision {
    data object Complete : IdentFscVerifyDecision
    data object Rejected : IdentFscVerifyDecision
}

internal object IdentFscFlow {

    /** Applies one PATCH's fields on top of the current state - a later call may correct an earlier field. */
    fun merge(state: IdentFscState, input: IdentFscInput): IdentFscState = IdentFscState(
        kvnr = input.kvnr ?: state.kvnr,
        name = input.name ?: state.name,
        vorname = input.vorname ?: state.vorname,
        fscHash = input.fsc?.let { hash(it.trim()) } ?: state.fscHash,
        personId = input.personId ?: state.personId
    )

    fun decide(state: IdentFscState): IdentFscDecision {
        if (missingFields(state).isNotEmpty()) return IdentFscDecision.Incomplete
        val personId = state.personId ?: return IdentFscDecision.PersonNotFound
        return IdentFscDecision.Verify(personId, state.name.orEmpty(), state.vorname.orEmpty(), checkNotNull(state.fscHash))
    }

    fun decideVerification(throttled: Boolean, nameMatches: Boolean, codeValid: Boolean): IdentFscVerifyDecision =
        if (!throttled && nameMatches && codeValid) IdentFscVerifyDecision.Complete else IdentFscVerifyDecision.Rejected

    /**
     * Staged: `fsc` only ever appears once kvnr/name/vorname are all present, the same staging
     * `ident-eid` expresses through separate steps - this tool expresses it through this ordering
     * instead, since all its fields share the one `input` step (see [describe]).
     */
    fun missingFields(state: IdentFscState): List<String> {
        val lookupMissing = listOfNotNull(
            "kvnr".takeIf { state.kvnr.isNullOrBlank() },
            "name".takeIf { state.name.isNullOrBlank() },
            "vorname".takeIf { state.vorname.isNullOrBlank() }
        )
        if (lookupMissing.isNotEmpty()) return lookupMissing
        return listOfNotNull("fsc".takeIf { state.fscHash.isNullOrBlank() })
    }

    /** Same derivation for start/patch/read - one place turns a state into `next.step`/`stepData`. */
    fun describe(state: IdentFscState): Pair<String, Map<String, Any?>> = "input" to mapOf("missingFields" to missingFields(state))

    fun evidenceHash(kvnr: String, fscHash: String): String = "sha256:" + hash("$kvnr:$fscHash")

    private fun hash(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
