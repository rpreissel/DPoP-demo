package com.example.dpop.id_fsc.internal

import com.example.dpop.ext_stammdaten.Freischaltcodes
import java.security.MessageDigest
import com.example.dpop.tool_spi.StepData
import com.example.dpop.tool_spi.MissingFields
import java.time.LocalDate

/**
 * Pure state of the ident-fsc flow (docs/03-tool-architektur.md #3, the optional Flow pattern) -
 * never leaves this file. One flat shape, not a sealed hierarchy: unlike a two-field OTP flow,
 * five independently-suppliable fields don't collapse into a small number of named positions -
 * [missingFields] derives what's still needed from whichever combination is present.
 */
internal data class IdentFscState(
    val kvnr: String? = null,
    val name: String? = null,
    val vorname: String? = null,
    val geburtsdatum: LocalDate? = null,
    val fscHash: String? = null,
    val personId: Long? = null
)

/** What one PATCH submitted - all optional, exactly the API's "only the changed part" rule. */
internal data class IdentFscInput(
    val kvnr: String? = null,
    val name: String? = null,
    val vorname: String? = null,
    val geburtsdatum: LocalDate? = null,
    val fsc: String? = null,
    val personId: Long? = null
) {
    /** Whether this PATCH touched the personal data - which is then checked again, right away. */
    val touchesPersonalien: Boolean get() = kvnr != null || name != null || vorname != null || geburtsdatum != null
}

/**
 * What [IdentFscFlow.decide] concluded from a merged state. The personal data is checked the
 * moment it is complete, before `fsc` is even asked for - so the stored personal data is only
 * ever incomplete or already verified, and no flag has to say which.
 */
internal sealed interface IdentFscDecision {
    data object Incomplete : IdentFscDecision

    /** The personal data is complete but its KVNR resolved no person. */
    data object PersonNotFound : IdentFscDecision

    /** The personal data was just (re-)supplied: check it against the register first. */
    data class VerifyPersonalien(
        val personId: Long,
        val name: String,
        val vorname: String,
        val geburtsdatum: LocalDate
    ) : IdentFscDecision

    /** The personal data stands verified and a code is present: check the code. */
    data class VerifyCode(val personId: Long, val fscHash: String) : IdentFscDecision
}

internal object IdentFscFlow {

    /**
     * Applies one PATCH's fields on top of the current state - a later call may correct an earlier
     * field. The person travels with the KVNR it was resolved from: a new KVNR that resolves no
     * one must not leave the previous KVNR's person standing.
     */
    fun merge(state: IdentFscState, input: IdentFscInput): IdentFscState = IdentFscState(
        kvnr = input.kvnr ?: state.kvnr,
        name = input.name ?: state.name,
        vorname = input.vorname ?: state.vorname,
        geburtsdatum = input.geburtsdatum ?: state.geburtsdatum,
        fscHash = input.fsc?.let { Freischaltcodes.digest(it.trim()) } ?: state.fscHash,
        personId = if (input.kvnr != null) input.personId else state.personId
    )

    fun decide(state: IdentFscState, input: IdentFscInput): IdentFscDecision {
        if (personalienMissing(state).isNotEmpty()) return IdentFscDecision.Incomplete
        val personId = state.personId ?: return IdentFscDecision.PersonNotFound
        if (input.touchesPersonalien) {
            return IdentFscDecision.VerifyPersonalien(
                personId, state.name.orEmpty(), state.vorname.orEmpty(), checkNotNull(state.geburtsdatum)
            )
        }
        val fscHash = state.fscHash ?: return IdentFscDecision.Incomplete
        return IdentFscDecision.VerifyCode(personId, fscHash)
    }

    /**
     * Rejected personal data is dropped as a whole, code included: the next [missingFields] asks
     * for the personal data again, never for a code that would belong to an unverified person.
     */
    fun rejectPersonalien(): IdentFscState = IdentFscState()

    /** A rejected code is dropped; the verified personal data stays, so only `fsc` is asked for again. */
    fun rejectCode(state: IdentFscState): IdentFscState = state.copy(fscHash = null)

    /**
     * Staged: `fsc` only ever appears once kvnr/name/vorname/geburtsdatum are all present - and,
     * since [decide] checks them the moment they are, verified. How many screens a client makes of
     * that is its own business (docs/10-frontend.md): the React form and the Keycloak template both
     * show the personal data first and the code second, purely from this list - the backend keeps
     * the one `input` step (see [describe]).
     */
    fun missingFields(state: IdentFscState): List<String> {
        val lookupMissing = personalienMissing(state)
        if (lookupMissing.isNotEmpty()) return lookupMissing
        return listOfNotNull("fsc".takeIf { state.fscHash.isNullOrBlank() })
    }

    private fun personalienMissing(state: IdentFscState): List<String> = listOfNotNull(
        "kvnr".takeIf { state.kvnr.isNullOrBlank() },
        "name".takeIf { state.name.isNullOrBlank() },
        "vorname".takeIf { state.vorname.isNullOrBlank() },
        "geburtsdatum".takeIf { state.geburtsdatum == null }
    )

    /** Same derivation for start/patch/read - one place turns a state into `next.step`/`stepData`. */
    fun describe(state: IdentFscState): Pair<String, StepData> = "input" to MissingFields(missingFields(state))

    fun evidenceHash(kvnr: String, fscHash: String): String = "sha256:" + hash("$kvnr:$fscHash")

    private fun hash(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
