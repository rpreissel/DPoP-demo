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
                    return ToolOutcome.Failed.Identification(Text("eID-PIN ungueltig"), attemptedPersonId = null)
                }
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
                        Claim(AttributeType.FAMILY_NAME, checkNotNull(decision.claimed.familyName), ClaimSource.of(descriptor.toolId), descriptor.maxAcr),
                        Claim(AttributeType.GIVEN_NAMES, checkNotNull(decision.claimed.givenNames), ClaimSource.of(descriptor.toolId), descriptor.maxAcr),
                        Claim(AttributeType.BIRTH_DATE, checkNotNull(decision.claimed.birthDate).toString(), ClaimSource.of(descriptor.toolId), descriptor.maxAcr),
                        Claim(AttributeType.STREET_ADDRESS, checkNotNull(decision.claimed.streetAddress), ClaimSource.of(descriptor.toolId), descriptor.maxAcr),
                        Claim(AttributeType.POSTAL_CODE, checkNotNull(decision.claimed.postalCode), ClaimSource.of(descriptor.toolId), descriptor.maxAcr),
                        Claim(AttributeType.LOCALITY, checkNotNull(decision.claimed.locality), ClaimSource.of(descriptor.toolId), descriptor.maxAcr),
                        Claim(AttributeType.EID_RESTRICTED_ID, decision.restrictedId, ClaimSource.of(descriptor.toolId), descriptor.maxAcr)
                    ),
                    auditDetails = mapOf(
                        "provider" to "eid-mock-service",
                        "providerTxId" to "EID-$toolSessionId",
                        "methodVersion" to "1.0",
                        "evidenceHash" to IdentEidFlow.evidenceHash(
                            listOf(
                                decision.restrictedId, decision.claimed.familyName, decision.claimed.givenNames,
                                decision.claimed.birthDate?.toString(), decision.claimed.streetAddress, decision.claimed.postalCode, decision.claimed.locality,
                            )
                        )
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

    private fun IdEidToolSession.toState(): IdentEidState =
        IdentEidState(familyName, givenNames, birthDate, streetAddress, postalCode, locality, restrictedId, pinHash)

    private fun IdEidToolSession.applyState(state: IdentEidState) {
        familyName = state.familyName
        givenNames = state.givenNames
        birthDate = state.birthDate
        streetAddress = state.streetAddress
        postalCode = state.postalCode
        locality = state.locality
        restrictedId = state.restrictedId
        pinHash = state.pinHash
    }
}
