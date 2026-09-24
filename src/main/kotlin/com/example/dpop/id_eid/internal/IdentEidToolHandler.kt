package com.example.dpop.id_eid.internal

import com.example.dpop.texts.Text
import com.example.dpop.id_eid.IdentEidDescriptor
import com.example.dpop.tool_spi.AttributeType
import com.example.dpop.tool_spi.Claim
import com.example.dpop.tool_spi.ToolOutcome
import com.example.dpop.tool_spi.ClaimSource
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * toolId=ident-eid. Attests what a simulated eID card shows - nothing else. Two steps, each its
 * own `nextStep` so the client can show a distinct screen: the card read itself (possession),
 * then a PIN (knowledge), mirroring the two factors a real eID run proves in one go.
 *
 * Nothing is typed beforehand and nobody is looked up: the card carries neither a KVNR nor a
 * person reference, so this tool asserts only what the card itself shows (name, vorname,
 * geburtsdatum, address, and the card's restricted identifier - the replaceable recognition
 * anchor, ADR-19) and stops there. Whether those attributes belong to a known account is
 * the central identity resolution's question, and binding them to a register person is
 * `ident-kvnr`'s (docs/12-entscheidungen.md ADR-18).
 *
 * Pure business logic; self-description lives in [IdentEidDescriptor].
 * Delegates field-merging and the ready-to-verify decision to [IdentEidFlow].
 */
@Component
class IdentEidToolHandler(
    private val descriptor: IdentEidDescriptor,
    private val repository: IdEidToolSessionRepository
) {

    /** Called directly by IdentEidToolController; nothing needs resolving before this can start. */
    @Transactional
    fun start(toolSessionId: UUID): ToolOutcome {
        repository.save(IdEidToolSession(toolSessionId = toolSessionId))
        return outcomeFor(IdentEidState())
    }

    /**
     * A wrong PIN is bounded by this tool session's own retry budget, the same way a real card
     * bounds PIN attempts itself - there is no account or person to throttle against here,
     * because this run resolves neither.
     */
    @Transactional
    fun patch(toolSessionId: UUID, fields: EidPatchFields): ToolOutcome {
        val data = checkNotNull(repository.findByIdOrNull(toolSessionId)) { "Unknown ident-eid tool session: $toolSessionId" }

        val merged = IdentEidFlow.merge(data.toState(), fields)
        data.applyState(merged)
        repository.save(data)

        return when (val decision = IdentEidFlow.decide(merged)) {
            IdentEidDecision.Incomplete -> outcomeFor(merged)

            is IdentEidDecision.Verify -> {
                if (!IdentEidFlow.pinMatchesMock(decision.pinHash)) {
                    return ToolOutcome.Failed(Text("eID-PIN ungueltig"))
                }
                val documentNumber = mockDocumentNumber(toolSessionId)
                ToolOutcome.Completed.Identified(
                    amr = listOf(descriptor.method),
                    achievedAcr = descriptor.maxAcr,
                    factorTypes = descriptor.factorTypes,
                    claims = listOf(
                        // Exactly what the card showed, on this procedure's own authority
                        // (ClaimSource.of(toolId)) - no PERSON_ID and no KVNR, because a card
                        // carries neither. Whether these attributes belong to a known person is
                        // the central resolution's question, and binding them to a register
                        // person is `ident-kvnr`'s (ADR-18). Address fields are claims like the
                        // name: the card bezeugte them, so the claim log records them. The
                        // restricted_id claim consolidates into the replaceable local anchor that
                        // recognizes an eid-identified Interessent on their next eid run (ADR-19).
                        // The auditDetails blob keeps only what no claim can carry (provider,
                        // transaction ids, evidence hash). geburtsdatum is an ISO date string
                        // via LocalDate.toString().
                        Claim(AttributeType.NAME, checkNotNull(decision.claimed.name), ClaimSource.of(descriptor.toolId), descriptor.maxAcr),
                        Claim(AttributeType.VORNAME, checkNotNull(decision.claimed.vorname), ClaimSource.of(descriptor.toolId), descriptor.maxAcr),
                        Claim(AttributeType.GEBURTSDATUM, checkNotNull(decision.claimed.geburtsdatum).toString(), ClaimSource.of(descriptor.toolId), descriptor.maxAcr),
                        Claim(AttributeType.STRASSE, checkNotNull(decision.claimed.strasse), ClaimSource.of(descriptor.toolId), descriptor.maxAcr),
                        Claim(AttributeType.PLZ, checkNotNull(decision.claimed.plz), ClaimSource.of(descriptor.toolId), descriptor.maxAcr),
                        Claim(AttributeType.ORT, checkNotNull(decision.claimed.ort), ClaimSource.of(descriptor.toolId), descriptor.maxAcr),
                        Claim(AttributeType.EID_RESTRICTED_ID, decision.restrictedId, ClaimSource.of(descriptor.toolId), descriptor.maxAcr)
                    ),
                    auditDetails = mapOf(
                        "provider" to "eid-mock-service",
                        "providerTxId" to "EID-$toolSessionId",
                        "methodVersion" to "1.0",
                        "documentNumber" to documentNumber,
                        "evidenceHash" to IdentEidFlow.evidenceHash(decision.pinHash, documentNumber)
                    )
                )
            }
        }
    }

    @Transactional(readOnly = true)
    fun read(toolSessionId: UUID): ToolOutcome {
        val data = checkNotNull(repository.findByIdOrNull(toolSessionId)) { "Unknown ident-eid tool session: $toolSessionId" }
        return outcomeFor(data.toState())
    }

    private fun outcomeFor(state: IdentEidState): ToolOutcome.InProgress {
        val (step, fields) = IdentEidFlow.describe(state)
        return ToolOutcome.InProgress(nextStep = step, stepData = fields)
    }

    private fun mockDocumentNumber(toolSessionId: UUID): String =
        "MOCK" + toolSessionId.toString().replace("-", "").take(9).uppercase()

    private fun IdEidToolSession.toState(): IdentEidState =
        IdentEidState(name, vorname, geburtsdatum, strasse, plz, ort, restrictedId, pinHash)

    private fun IdEidToolSession.applyState(state: IdentEidState) {
        name = state.name
        vorname = state.vorname
        geburtsdatum = state.geburtsdatum
        strasse = state.strasse
        plz = state.plz
        ort = state.ort
        restrictedId = state.restrictedId
        pinHash = state.pinHash
    }
}
