package com.example.dpop.id_eid.internal

import com.example.dpop.id_eid.IdentEidDescriptor
import com.example.dpop.tool_spi.AttributeType
import com.example.dpop.tool_spi.Claim
import com.example.dpop.tool_spi.ToolOutcome
import com.example.dpop.tool_spi.TrustAnchor
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * toolId=ident-eid. Resolves KVNR/name/vorname into a person, same seam as `ident-fsc`
 * (`IdentEidToolController` looks `personId` up via `PersonDirectory` since `id_eid` must not
 * depend on `ext_stammdaten` directly). Two further steps follow, each its own `nextStep` so the
 * client can show a distinct screen: a simulated eID card read that hands back the card's
 * Ausweisdaten (possession), then a PIN (knowledge) - mirroring the two factors a real eID run
 * proves in one go.
 *
 * Once all three are in, the handler attests what the card showed and stops there - the central
 * identity resolution (`IdentityResolver`, account module) owns the stammdaten consistency check
 * and the account question; this tool never sees accounts (docs/ideen/claims-modell-und-
 * vertrauensanker.md, "Identitaetsauflösung & Matching").
 *
 * Pure business logic; self-description lives in [IdentEidDescriptor].
 * Delegates field-merging and the ready-to-verify decision to [IdentEidFlow].
 */
@Component
class IdentEidToolHandler(
    private val descriptor: IdentEidDescriptor,
    private val repository: IdEidToolDataRepository
) {

    /** Called directly by IdentEidToolController; nothing needs resolving before this can start. */
    @Transactional
    fun start(toolSessionId: UUID): ToolOutcome {
        repository.save(IdEidToolData(toolSessionId = toolSessionId))
        return outcomeFor(IdentEidState())
    }

    /**
     * [throttled] folds into the ordinary "PIN invalid" answer rather than getting an error of
     * its own - a distinguishable lock response would turn this into a KVNR-existence oracle.
     */
    @Transactional
    fun patch(toolSessionId: UUID, fields: EidPatchFields, personId: Long?, throttled: Boolean): ToolOutcome {
        val data = checkNotNull(repository.findByIdOrNull(toolSessionId)) { "Unknown ident-eid tool session: $toolSessionId" }

        val merged = IdentEidFlow.merge(data.toState(), fields, personId)
        data.applyState(merged)
        repository.save(data)

        return when (val decision = IdentEidFlow.decide(merged)) {
            IdentEidDecision.Incomplete -> outcomeFor(merged)

            IdentEidDecision.PersonNotFound -> ToolOutcome.Failed("Person zu dieser KVNR nicht gefunden")

            is IdentEidDecision.Verify -> {
                if (throttled || !IdentEidFlow.pinMatchesMock(decision.pinHash)) {
                    return ToolOutcome.Failed("eID-PIN ungueltig", attemptedPersonId = decision.personId)
                }
                // The former stammdaten consistency check lives in the central identity
                // resolution now (IdentityResolver): a contradiction surfaces as an
                // IdentityConflictException in the journey, not as a tool-PATCH failure here.

                val documentNumber = mockDocumentNumber(toolSessionId)
                ToolOutcome.Completed.Identified(
                    personId = decision.personId,
                    amr = listOf(descriptor.method),
                    achievedAcr = descriptor.maxAcr,
                    factorTypes = descriptor.factorTypes,
                    claims = listOf(
                        // An eID run is the proving tool itself: the card data was MATCHED
                        // against the master data, but what this run asserts is what the card
                        // showed - hence this tool's own id as the trust anchor. Address fields
                        // (strasse/hausnummer/plz/ort) stay in the auditDetails blob: no
                        // anchor or projection consumer exists for them, so they are not
                        // claims (see IdentEidDescriptor.claims). geburtsdatum is an ISO date
                        // string via LocalDate.toString().
                        Claim(AttributeType.PERSON_ID, decision.personId.toString(), TrustAnchor.of(descriptor.toolId), descriptor.maxAcr),
                        Claim(AttributeType.KVNR, checkNotNull(merged.kvnr), TrustAnchor.of(descriptor.toolId), descriptor.maxAcr),
                        Claim(AttributeType.NAME, checkNotNull(decision.claimed.name), TrustAnchor.of(descriptor.toolId), descriptor.maxAcr),
                        Claim(AttributeType.VORNAME, checkNotNull(decision.claimed.vorname), TrustAnchor.of(descriptor.toolId), descriptor.maxAcr),
                        Claim(AttributeType.GEBURTSDATUM, checkNotNull(decision.claimed.geburtsdatum).toString(), TrustAnchor.of(descriptor.toolId), descriptor.maxAcr)
                    ),
                    auditDetails = mapOf(
                        "provider" to "eid-mock-service",
                        "providerTxId" to "EID-$toolSessionId",
                        "methodVersion" to "1.0",
                        "documentNumber" to documentNumber,
                        "evidenceHash" to IdentEidFlow.evidenceHash(merged.kvnr.orEmpty(), decision.pinHash, documentNumber)
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
        return ToolOutcome.InProgress(nextStep = step, data = fields)
    }

    private fun mockDocumentNumber(toolSessionId: UUID): String =
        "MOCK" + toolSessionId.toString().replace("-", "").take(9).uppercase()

    private fun IdEidToolData.toState(): IdentEidState =
        IdentEidState(kvnr, name, vorname, personId, geburtsdatum, strasse, hausnummer, plz, ort, pinHash)

    private fun IdEidToolData.applyState(state: IdentEidState) {
        kvnr = state.kvnr
        name = state.name
        vorname = state.vorname
        personId = state.personId
        geburtsdatum = state.geburtsdatum
        strasse = state.strasse
        hausnummer = state.hausnummer
        plz = state.plz
        ort = state.ort
        pinHash = state.pinHash
    }
}
