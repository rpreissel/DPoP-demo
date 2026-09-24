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

/**
 * What a relying party can ask Nect for. Nect has no public field list; what it can hand on is
 * bounded by what each document itself delivers (docs/ideen/ident-nect.md, Abschnitt 7) - this
 * lists the part of that a relying party of this demo asks for, not everything a document holds.
 */
enum class NectAttribute(val wireName: String) {
    FAMILY_NAME("family_name"),
    GIVEN_NAMES("given_names"),
    BIRTH_DATE("birth_date"),
    ADDRESS("address"),
    /** eID only: the card's restricted identifier (§18 PAuswG "dienste- und kartenspezifisches Kennzeichen"). */
    EID_PSEUDONYM("eid_pseudonym"),
    /** ePass only: document number and issuing state from the chip's MRZ data (ICAO 9303 DG1). */
    DOCUMENT_ID("document_id");

    companion object {
        fun of(wireName: String): NectAttribute =
            entries.firstOrNull { it.wireName == wireName } ?: throw NectRejectedException("Unbekanntes Attribut: $wireName")
    }
}

/** Which document the user identified with on the jump page, and what that document can deliver at all. */
enum class NectProcedure(val wireName: String, val deliverable: Set<NectAttribute>) {
    /** eID card (§18 PAuswG): address as one place of residence; also a card pseudonym. */
    EID("eid", setOf(NectAttribute.FAMILY_NAME, NectAttribute.GIVEN_NAMES, NectAttribute.BIRTH_DATE, NectAttribute.ADDRESS, NectAttribute.EID_PSEUDONYM)),
    /** Passport chip (ICAO 9303 DG1): read whole, no address - but a document number. */
    EPASS("epass", setOf(NectAttribute.FAMILY_NAME, NectAttribute.GIVEN_NAMES, NectAttribute.BIRTH_DATE, NectAttribute.DOCUMENT_ID)),
    /** EUDI wallet PID (PID rulebook): address optional, and no pseudonym towards the relying party. */
    EUDI("eudi", setOf(NectAttribute.FAMILY_NAME, NectAttribute.GIVEN_NAMES, NectAttribute.BIRTH_DATE, NectAttribute.ADDRESS));

    companion object {
        fun of(wireName: String): NectProcedure =
            entries.firstOrNull { it.wireName == wireName } ?: throw NectRejectedException("Unbekanntes Verfahren: $wireName")
    }
}

/**
 * What a document attested - and, on redeem, what of it the relying party asked for. Everything
 * nullable: a passport carries no address, a wallet shares only what its holder released, and
 * Nect hands on only what was requested - absent means "not attested", never "empty".
 */
data class NectAttributes(
    val name: String? = null,
    val vorname: String? = null,
    val geburtsdatum: LocalDate? = null,
    val strasse: String? = null,
    val hausnummer: String? = null,
    val plz: String? = null,
    val ort: String? = null,
    /** [NectAttribute.EID_PSEUDONYM]. */
    val restrictedId: String? = null,
    /** [NectAttribute.DOCUMENT_ID]. */
    val documentNumber: String? = null,
    val issuingState: String? = null
) {
    internal fun present(): Set<NectAttribute> = buildSet {
        if (name != null) add(NectAttribute.FAMILY_NAME)
        if (vorname != null) add(NectAttribute.GIVEN_NAMES)
        if (geburtsdatum != null) add(NectAttribute.BIRTH_DATE)
        if (listOf(strasse, hausnummer, plz, ort).any { it != null }) add(NectAttribute.ADDRESS)
        if (restrictedId != null) add(NectAttribute.EID_PSEUDONYM)
        if (documentNumber != null || issuingState != null) add(NectAttribute.DOCUMENT_ID)
    }

    internal fun only(requested: Set<NectAttribute>): NectAttributes {
        fun <T> keep(attribute: NectAttribute, value: T?): T? = value.takeIf { attribute in requested }
        return NectAttributes(
            name = keep(NectAttribute.FAMILY_NAME, name),
            vorname = keep(NectAttribute.GIVEN_NAMES, vorname),
            geburtsdatum = keep(NectAttribute.BIRTH_DATE, geburtsdatum),
            strasse = keep(NectAttribute.ADDRESS, strasse),
            hausnummer = keep(NectAttribute.ADDRESS, hausnummer),
            plz = keep(NectAttribute.ADDRESS, plz),
            ort = keep(NectAttribute.ADDRESS, ort),
            restrictedId = keep(NectAttribute.EID_PSEUDONYM, restrictedId),
            documentNumber = keep(NectAttribute.DOCUMENT_ID, documentNumber),
            issuingState = keep(NectAttribute.DOCUMENT_ID, issuingState)
        )
    }
}

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

/** The case as the jump page sees it: its state, and what the relying party asked for. */
data class NectCaseView(val caseId: UUID, val status: String, val requested: List<String>)

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

    /**
     * Opens a case asking for [requested]; the user is sent to [NectCaseRef.jumpUrl] and comes back
     * to [callbackUri]. Redeeming it yields no more than [requested], whatever the document held.
     */
    @Transactional
    fun createCase(callbackUri: String, requested: Set<NectAttribute>): NectCaseRef {
        require(requested.isNotEmpty()) { "A case must ask for something" }
        val case = cases.save(
            NectCase(
                id = UUID.randomUUID(),
                callbackUri = callbackUri,
                requested = requested.joinToString(",") { it.wireName },
                createdAt = Instant.now()
            )
        )
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
        cases.findByIdOrNull(caseId)?.let { NectCaseView(caseId, it.status.name, requestedOf(it).map(NectAttribute::wireName)) }

    /**
     * The user finished identification with [procedure]; [attributes] is what the document yielded.
     * The mock checks what a real one would: the eID PIN (test value 123456) and the passport's
     * [expiryDate] - an expired passport fails the case, a wrong PIN is refused and the page asks
     * again. The expiry is Nect's own check and is not handed on. Of the document's data, only what
     * the relying party requested is kept. Returns where to send the browser.
     */
    @Transactional
    fun complete(caseId: UUID, procedure: NectProcedure, attributes: NectAttributes, pin: String?, expiryDate: LocalDate? = null): String {
        val case = openCase(caseId)
        if (procedure == NectProcedure.EID && pin != MOCK_EID_PIN) throw NectRejectedException("PIN falsch (Testwert: $MOCK_EID_PIN)")
        val undeliverable = attributes.present() - procedure.deliverable
        if (undeliverable.isNotEmpty()) {
            throw NectRejectedException("Verfahren ${procedure.wireName} liefert nicht: ${undeliverable.joinToString { it.wireName }}")
        }
        if (attributes.name.isNullOrBlank() || attributes.vorname.isNullOrBlank()) {
            throw NectRejectedException("Name und Vorname werden mindestens benötigt")
        }
        if (procedure == NectProcedure.EPASS && expiryDate != null && expiryDate.isBefore(LocalDate.now())) {
            return finish(case, NectCaseStatus.FAILED, reason = "Reisepass abgelaufen")
        }
        case.procedure = procedure.wireName
        case.result = json.writeValueAsString(attributes.only(requestedOf(case)))
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

    private fun requestedOf(case: NectCase): Set<NectAttribute> =
        checkNotNull(case.requested).split(",").map(NectAttribute::of).toSet()

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
