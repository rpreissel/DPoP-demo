package com.example.dpop.id_eid.internal

import com.example.dpop.tool_api.ClaimedIdentity
import java.security.MessageDigest
import java.time.LocalDate

/**
 * Pure state of the ident-eid flow (docs/03-tool-architektur.md #3, the optional Flow pattern) -
 * never leaves this file. Three stages, each its own `next.step` (input -> card -> pin) so the
 * client can show a distinct screen, unlike `ident-fsc`'s single shared step.
 */
internal data class IdentEidState(
    val kvnr: String? = null,
    val name: String? = null,
    val vorname: String? = null,
    val personId: Long? = null,
    val geburtsdatum: LocalDate? = null,
    val strasse: String? = null,
    val hausnummer: String? = null,
    val plz: String? = null,
    val ort: String? = null,
    val pinHash: String? = null
)

/** What [IdentEidFlow.decide] concluded once a state is fully filled in. */
internal sealed interface IdentEidDecision {
    data object Incomplete : IdentEidDecision
    data object PersonNotFound : IdentEidDecision
    data class Verify(val personId: Long, val claimed: ClaimedIdentity, val pinHash: String) : IdentEidDecision
}

internal object IdentEidFlow {

    /** Applies one PATCH's fields (plus a separately-resolved [personId]) on top of the current state. */
    fun merge(state: IdentEidState, fields: EidPatchFields, personId: Long?): IdentEidState = IdentEidState(
        kvnr = fields.kvnr ?: state.kvnr,
        name = fields.name ?: state.name,
        vorname = fields.vorname ?: state.vorname,
        personId = personId ?: state.personId,
        geburtsdatum = fields.geburtsdatum ?: state.geburtsdatum,
        strasse = fields.strasse ?: state.strasse,
        hausnummer = fields.hausnummer ?: state.hausnummer,
        plz = fields.plz ?: state.plz,
        ort = fields.ort ?: state.ort,
        pinHash = fields.pin?.let { hash(it.trim()) } ?: state.pinHash
    )

    fun decide(state: IdentEidState): IdentEidDecision {
        if (!hasLookupFields(state) || !hasCardFields(state) || state.pinHash.isNullOrBlank()) return IdentEidDecision.Incomplete
        val personId = state.personId ?: return IdentEidDecision.PersonNotFound
        val claimed = ClaimedIdentity(
            name = state.name.orEmpty(),
            vorname = state.vorname.orEmpty(),
            geburtsdatum = checkNotNull(state.geburtsdatum),
            strasse = state.strasse.orEmpty(),
            hausnummer = state.hausnummer.orEmpty(),
            plz = state.plz.orEmpty(),
            ort = state.ort.orEmpty()
        )
        return IdentEidDecision.Verify(personId, claimed, checkNotNull(state.pinHash))
    }

    /** Constant-time, and against the stored hash - the PIN itself is never persisted. */
    fun pinMatchesMock(pinHash: String): Boolean = MessageDigest.isEqual(pinHash.toByteArray(), hash(MOCK_PIN).toByteArray())

    /** Same derivation for start/patch/read - one place turns a state into `next.step`/`stepData`. */
    fun describe(state: IdentEidState): Pair<String, Map<String, Any?>> = when {
        !hasLookupFields(state) -> "input" to mapOf("missingFields" to LOOKUP_FIELDS)
        !hasCardFields(state) -> "card" to mapOf("missingFields" to CARD_FIELDS)
        else -> "pin" to mapOf("missingFields" to PIN_FIELDS)
    }

    fun evidenceHash(kvnr: String, pinHash: String, documentNumber: String): String = "sha256:" + hash("$kvnr:$pinHash:$documentNumber")

    private fun hasLookupFields(state: IdentEidState) =
        !state.kvnr.isNullOrBlank() && !state.name.isNullOrBlank() && !state.vorname.isNullOrBlank()

    private fun hasCardFields(state: IdentEidState) =
        state.geburtsdatum != null && !state.strasse.isNullOrBlank() && !state.hausnummer.isNullOrBlank() &&
            !state.plz.isNullOrBlank() && !state.ort.isNullOrBlank()

    private fun hash(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    val LOOKUP_FIELDS = listOf("kvnr", "name", "vorname")
    val CARD_FIELDS = listOf("geburtsdatum", "strasse", "hausnummer", "plz", "ort")
    val PIN_FIELDS = listOf("pin")

    /** Fixed test PIN for the mock, same role as `ident-fsc`'s `VALIDCODE` (docs/08-projektrahmen.md P-5/P-6). */
    const val MOCK_PIN = "123456"
}
