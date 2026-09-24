package com.example.dpop.id_nect.internal

import com.example.dpop.texts.Text
import com.example.dpop.id_nect.IdentNectDescriptor
import com.example.dpop.id_nect.api.v1.NectRedirectStep
import com.example.dpop.nect_mock.NectAttribute
import com.example.dpop.nect_mock.NectAttributes
import com.example.dpop.nect_mock.NectFailure
import com.example.dpop.nect_mock.NectIdent
import com.example.dpop.nect_mock.NectProcedure
import com.example.dpop.nect_mock.NectResult
import com.example.dpop.tool_spi.AcrLevel
import com.example.dpop.tool_spi.AttributeType
import com.example.dpop.tool_spi.Claim
import com.example.dpop.tool_spi.ClaimSource
import com.example.dpop.tool_spi.FactorType
import com.example.dpop.tool_spi.ToolOutcome
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** Where Nect sends the user back to; the app channel picks `nectCaseId` up from its URL. */
internal const val NECT_CALLBACK_URI = "/app/"

/**
 * What we ask Nect for - the same as `ident-eid` reads from the card: name, given names and birth
 * date (what `ident-kvnr` matches on), the address, and each document's own anchor. Nect hands on
 * the part of it the chosen document can deliver: a passport has no address, a wallet PID no
 * pseudonym (docs/ideen/ident-nect.md, Abschnitt 7).
 */
internal val NECT_REQUESTED = setOf(
    NectAttribute.FAMILY_NAME,
    NectAttribute.GIVEN_NAMES,
    NectAttribute.BIRTH_DATE,
    NectAttribute.ADDRESS,
    NectAttribute.EID_PSEUDONYM,
    NectAttribute.DOCUMENT_ID
)

/**
 * toolId=ident-nect. The run opens a case at Nect and hands the client its jump URL; the user
 * identifies there and comes back with the case id, which the client reports. The result itself
 * never travels through the client: this handler redeems it from Nect server-side, once, and only
 * for the case this tool session opened - a foreign or replayed case id fails.
 *
 * Like `ident-eid` it attests what the document showed and resolves nobody (ADR-18). What each
 * run proved depends on the document the user picked at Nect: amr `nect-<procedure>`, the
 * procedure's own level and factors (docs/ideen/ident-nect.md).
 */
@Component
class IdentNectToolHandler(
    private val descriptor: IdentNectDescriptor,
    private val repository: IdNectToolSessionRepository,
    private val nect: NectIdent
) {

    @Transactional
    fun start(toolSessionId: UUID): ToolOutcome {
        val case = nect.createCase(NECT_CALLBACK_URI, NECT_REQUESTED)
        repository.save(IdNectToolSession(toolSessionId = toolSessionId, caseId = case.caseId))
        return redirect(case.caseId, case.jumpUrl)
    }

    /** [retry] opens a fresh case - the old one may be spent or abandoned. */
    @Transactional
    fun patch(toolSessionId: UUID, caseId: UUID?, retry: Boolean): ToolOutcome {
        val data = checkNotNull(repository.findByIdOrNull(toolSessionId)) { "Unknown ident-nect tool session: $toolSessionId" }
        if (retry) {
            val case = nect.createCase(NECT_CALLBACK_URI, NECT_REQUESTED)
            data.caseId = case.caseId
            repository.save(data)
            return redirect(case.caseId, case.jumpUrl)
        }
        if (caseId == null || caseId != data.caseId) {
            return ToolOutcome.Failed(Text("Nect-Vorgang gehört nicht zu diesem Ablauf"))
        }
        return when (val result = nect.redeem(caseId)) {
            null -> ToolOutcome.Failed(Text("Nect-Vorgang unbekannt oder bereits eingelöst"))
            NectResult.Open -> ToolOutcome.Failed(Text("Nect-Vorgang noch nicht abgeschlossen"))
            NectResult.Cancelled -> ToolOutcome.Failed(Text("Identifizierung bei Nect abgebrochen"))
            is NectResult.Failed -> ToolOutcome.Failed(
                when (result.reason) {
                    NectFailure.PASSPORT_EXPIRED -> Text("Nect: Der Reisepass ist abgelaufen")
                    NectFailure.SELFIE_MISMATCH -> Text("Nect: Das Selfie passt nicht zum Passbild")
                    NectFailure.SIMULATED -> Text("Nect: Identifizierung fehlgeschlagen")
                }
            )
            is NectResult.Identified -> identified(toolSessionId, caseId, result)
        }
    }

    @Transactional(readOnly = true)
    fun read(toolSessionId: UUID): ToolOutcome {
        val data = checkNotNull(repository.findByIdOrNull(toolSessionId)) { "Unknown ident-nect tool session: $toolSessionId" }
        val caseId = checkNotNull(data.caseId)
        return redirect(caseId, nect.jumpUrl(caseId))
    }

    private fun redirect(caseId: UUID, jumpUrl: String) =
        ToolOutcome.InProgress(nextStep = "redirect", stepData = NectRedirectStep(jumpUrl = jumpUrl, caseId = caseId))

    private fun identified(toolSessionId: UUID, caseId: UUID, result: NectResult.Identified): ToolOutcome.Completed.Identified {
        val level = levelOf(result.procedure)
        val a = result.attributes
        val source = ClaimSource.of(descriptor.toolId)
        val values = listOfNotNull(
            a.name?.let { AttributeType.NAME to it },
            a.vorname?.let { AttributeType.VORNAME to it },
            a.geburtsdatum?.let { AttributeType.GEBURTSDATUM to it.toString() },
            a.strasse?.let { AttributeType.STRASSE to it },
            a.plz?.let { AttributeType.PLZ to it },
            a.ort?.let { AttributeType.ORT to it },
            // Only the eID chip carries the card pseudonym (ADR-19); a passport or wallet
            // claim of it would not be the same anchor.
            a.restrictedId?.takeIf { result.procedure == NectProcedure.EID }?.let { AttributeType.EID_RESTRICTED_ID to it }
        )
        return ToolOutcome.Completed.Identified(
            amr = listOf("nect-${result.procedure.wireName}"),
            achievedAcr = level,
            factorTypes = factorsOf(result.procedure),
            claims = values.map { (type, value) -> Claim(type, value, source, level) },
            auditDetails = auditOf(toolSessionId, caseId, result.procedure, a)
        )
    }

    private fun auditOf(toolSessionId: UUID, caseId: UUID, procedure: NectProcedure, a: NectAttributes): Map<String, String> =
        buildMap {
            put("provider", "nect-mock")
            put("providerTxId", caseId.toString())
            put("toolSessionId", toolSessionId.toString())
            put("procedure", procedure.wireName)
            a.documentNumber?.let { put("documentNumber", it) }
            a.issuingState?.let { put("issuingState", it) }
        }

    private companion object {
        // A passport read (chip + selfie match) proves the document but not the holder the way
        // an eID PIN or a wallet's PID binding does - hence substantial, not high.
        fun levelOf(procedure: NectProcedure): AcrLevel = when (procedure) {
            NectProcedure.EID -> AcrLevel.LOA3
            NectProcedure.EPASS -> AcrLevel.LOA2
            NectProcedure.EUDI -> AcrLevel.LOA3
        }

        fun factorsOf(procedure: NectProcedure): Set<FactorType> = when (procedure) {
            NectProcedure.EID -> setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE)
            NectProcedure.EPASS -> setOf(FactorType.POSSESSION, FactorType.INHERENCE)
            NectProcedure.EUDI -> setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE)
        }
    }
}
