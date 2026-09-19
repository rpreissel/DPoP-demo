package com.example.dpop.id_eid.internal

import com.example.dpop.tool_api.ClaimedIdentity
import java.security.MessageDigest
import java.time.LocalDate

/**
 * Pure state of the ident-eid flow (docs/03-tool-architektur.md #3, the optional Flow pattern) -
 * never leaves this file. Two stages, each its own `next.step` (card -> pin) so the client can
 * show a distinct screen, unlike `ident-fsc`'s single shared step.
 *
 * Everything here is what the simulated card itself carries - there is deliberately no KVNR and
 * no person reference: a real eID card has neither, and resolving one is `ident-kvnr`'s job
 * (docs/12-entscheidungen.md ADR-18).
 */
internal data class IdentEidState(
    val name: String? = null,
    val vorname: String? = null,
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
    data class Verify(val claimed: ClaimedIdentity, val pinHash: String) : IdentEidDecision
}

internal object IdentEidFlow {

    /** Applies one PATCH's fields on top of the current state. */
    fun merge(state: IdentEidState, fields: EidPatchFields): IdentEidState = IdentEidState(
        name = fields.name ?: state.name,
        vorname = fields.vorname ?: state.vorname,
        geburtsdatum = fields.geburtsdatum ?: state.geburtsdatum,
        strasse = fields.strasse ?: state.strasse,
        hausnummer = fields.hausnummer ?: state.hausnummer,
        plz = fields.plz ?: state.plz,
        ort = fields.ort ?: state.ort,
        pinHash = fields.pin?.let { hash(it.trim()) } ?: state.pinHash
    )

    fun decide(state: IdentEidState): IdentEidDecision {
        if (!hasCardFields(state) || state.pinHash.isNullOrBlank()) return IdentEidDecision.Incomplete
        val claimed = ClaimedIdentity(
            name = state.name.orEmpty(),
            vorname = state.vorname.orEmpty(),
            geburtsdatum = checkNotNull(state.geburtsdatum),
            strasse = state.strasse.orEmpty(),
            hausnummer = state.hausnummer.orEmpty(),
            plz = state.plz.orEmpty(),
            ort = state.ort.orEmpty()
        )
        return IdentEidDecision.Verify(claimed, checkNotNull(state.pinHash))
    }

    /** Constant-time, and against the stored hash - the PIN itself is never persisted. */
    fun pinMatchesMock(pinHash: String): Boolean = MessageDigest.isEqual(pinHash.toByteArray(), hash(MOCK_PIN).toByteArray())

    /** Same derivation for start/patch/read - one place turns a state into `next.step`/`stepData`. */
    fun describe(state: IdentEidState): Pair<String, Map<String, Any?>> = when {
        !hasCardFields(state) -> "card" to mapOf("missingFields" to CARD_FIELDS)
        else -> "pin" to mapOf("missingFields" to PIN_FIELDS)
    }

    fun evidenceHash(pinHash: String, documentNumber: String): String = "sha256:" + hash("$pinHash:$documentNumber")

    private fun hasCardFields(state: IdentEidState) =
        !state.name.isNullOrBlank() && !state.vorname.isNullOrBlank() && state.geburtsdatum != null &&
            !state.strasse.isNullOrBlank() && !state.hausnummer.isNullOrBlank() &&
            !state.plz.isNullOrBlank() && !state.ort.isNullOrBlank()

    private fun hash(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    /** Everything the card itself shows - read in one go, nothing typed by the user beforehand. */
    val CARD_FIELDS = listOf("name", "vorname", "geburtsdatum", "strasse", "hausnummer", "plz", "ort")
    val PIN_FIELDS = listOf("pin")

    /** Fixed test PIN for the mock, same role as `ident-fsc`'s `VALIDCODE` (docs/08-projektrahmen.md P-5/P-6). */
    const val MOCK_PIN = "123456"
}
