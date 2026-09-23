package com.example.dpop.nect_mock

import com.example.dpop.nect_mock.internal.NectCase
import com.example.dpop.nect_mock.internal.NectCaseRepository
import com.example.dpop.nect_mock.internal.NectCaseStatus
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Which document the user identified with on the jump page. */
enum class NectProcedure(val wireName: String) {
    EID("eid"), EPASS("epass"), EUDI("eudi");

    companion object {
        fun of(wireName: String): NectProcedure =
            entries.firstOrNull { it.wireName == wireName } ?: throw NectRejectedException("Unbekanntes Verfahren: $wireName")
    }
}

/**
 * What a document attested. Everything nullable: a passport carries no address, and a wallet
 * shares only what its holder released (selective disclosure) - absent means "not attested",
 * never "empty".
 */
data class NectAttributes(
    val name: String? = null,
    val vorname: String? = null,
    val geburtsdatum: LocalDate? = null,
    val strasse: String? = null,
    val hausnummer: String? = null,
    val plz: String? = null,
    val ort: String? = null,
    /** eID only: the card's restricted identifier (pseudonym per service provider). */
    val restrictedId: String? = null,
    /** ePass only. */
    val documentNumber: String? = null,
    val issuingState: String? = null,
    val expiryDate: LocalDate? = null,
    /** EUDI wallet only: the wallet's pseudonym towards this relying party. */
    val walletPseudonym: String? = null
)

/** A case as the relying party opened it: where to send the user. */
data class NectCaseRef(val caseId: UUID, val jumpUrl: String)

/** What redeeming a case yields - exactly once. */
sealed interface NectResult {
    data class Identified(val procedure: NectProcedure, val attributes: NectAttributes) : NectResult
    data class Failed(val reason: String) : NectResult
    data object Cancelled : NectResult
    /** The user has not finished on the jump page yet. */
    data object Open : NectResult
}

/** The case as the jump page sees it. */
data class NectCaseView(val caseId: UUID, val status: String)

/** Raised when the jump page asks for something Nect does not accept (unknown case, closed case, wrong PIN). */
class NectRejectedException(message: String) : RuntimeException(message)

/**
 * The simulated Nect service (docs/ideen/ident-nect.md). Two audiences, like `KobilSsms`:
 * [createCase] and [redeem] are what the relying party's backend calls; [caseView], [complete],
 * [fail] and [cancel] are what the jump page does, reached over `/mock-nect/...`.
 */
@Service
class NectIdent(private val cases: NectCaseRepository) {

    private val json = jacksonObjectMapper()

    // ------------------------------------------------------------------ relying-party side

    /** Opens a case; the user is sent to [NectCaseRef.jumpUrl] and comes back to [callbackUri]. */
    @Transactional
    fun createCase(callbackUri: String): NectCaseRef {
        val case = cases.save(NectCase(id = UUID.randomUUID(), callbackUri = callbackUri, createdAt = Instant.now()))
        return NectCaseRef(checkNotNull(case.id), jumpUrl(checkNotNull(case.id)))
    }

    /** Where the user identifies for [caseId] - Nect's own page, not the relying party's. */
    fun jumpUrl(caseId: UUID): String = "/nect/?case=$caseId"

    /**
     * The case's outcome, handed out once: a finished case is closed by this call, a second call
     * (or an unknown case) answers null. An open case answers [NectResult.Open] and stays open.
     */
    @Transactional
    fun redeem(caseId: UUID): NectResult? {
        val case = cases.findByIdOrNull(caseId) ?: return null
        if (case.redeemedAt != null) return null
        val result = when (case.status) {
            NectCaseStatus.OPEN -> return NectResult.Open
            NectCaseStatus.COMPLETED -> NectResult.Identified(
                NectProcedure.of(checkNotNull(case.procedure)),
                json.readValue(checkNotNull(case.result), NectAttributes::class.java)
            )
            NectCaseStatus.FAILED -> NectResult.Failed(case.reason ?: "Identifizierung fehlgeschlagen")
            NectCaseStatus.CANCELLED -> NectResult.Cancelled
        }
        case.redeemedAt = Instant.now()
        return result
    }

    // ------------------------------------------------------------------ jump-page side

    @Transactional(readOnly = true)
    fun caseView(caseId: UUID): NectCaseView? =
        cases.findByIdOrNull(caseId)?.let { NectCaseView(caseId, it.status.name) }

    /**
     * The user finished identification with [procedure]. The mock checks what a real one would:
     * the eID PIN (test value 123456) and the passport's expiry - an expired passport fails the
     * case, a wrong PIN is refused and the page asks again. Returns where to send the browser.
     */
    @Transactional
    fun complete(caseId: UUID, procedure: NectProcedure, attributes: NectAttributes, pin: String?): String {
        val case = openCase(caseId)
        if (procedure == NectProcedure.EID && pin != MOCK_EID_PIN) throw NectRejectedException("PIN falsch (Testwert: $MOCK_EID_PIN)")
        if (attributes.name.isNullOrBlank() || attributes.vorname.isNullOrBlank()) {
            throw NectRejectedException("Name und Vorname werden mindestens benötigt")
        }
        val expiry = attributes.expiryDate
        if (procedure == NectProcedure.EPASS && expiry != null && expiry.isBefore(LocalDate.now())) {
            return finish(case, NectCaseStatus.FAILED, reason = "Reisepass abgelaufen")
        }
        case.procedure = procedure.wireName
        case.result = json.writeValueAsString(attributes)
        return finish(case, NectCaseStatus.COMPLETED)
    }

    /** Demo switch: the identification failed for [reason] (e.g. selfie mismatch). */
    @Transactional
    fun fail(caseId: UUID, reason: String): String = finish(openCase(caseId), NectCaseStatus.FAILED, reason)

    @Transactional
    fun cancel(caseId: UUID): String = finish(openCase(caseId), NectCaseStatus.CANCELLED)

    private fun openCase(caseId: UUID): NectCase {
        val case = cases.findByIdOrNull(caseId) ?: throw NectRejectedException("Unbekannter Vorgang")
        if (case.status != NectCaseStatus.OPEN) throw NectRejectedException("Vorgang ist bereits abgeschlossen")
        return case
    }

    private fun finish(case: NectCase, status: NectCaseStatus, reason: String? = null): String {
        case.status = status
        case.reason = reason
        case.finishedAt = Instant.now()
        val uri = checkNotNull(case.callbackUri)
        return uri + (if ('?' in uri) "&" else "?") + "nectCaseId=${case.id}"
    }

    private companion object {
        const val MOCK_EID_PIN = "123456"
    }
}
