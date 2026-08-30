package com.example.dpop.id_fsc.internal

import com.example.dpop.id_fsc.IdentFscDescriptor
import com.example.dpop.tool_api.PersonDirectory
import com.example.dpop.tool_spi.ToolOutcome
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * toolId=ident-fsc (docs/06-ablaeufe.md #2). Resolves KVNR/name/vorname/FSC into a person -
 * that resolution *is* the module's contribution, not just a yes/no check.
 *
 * [patch]'s [personId] parameter arrives pre-resolved: IdentFscToolController looks it up via
 * ext_stammdaten when a kvnr is supplied, since id_fsc must not depend on that module directly
 * (docs/08-projektrahmen.md #3: leaf modules stay decoupled from each other). The name check
 * goes back out over the same [PersonDirectory] port, exactly as `ident-eid` verifies its
 * Ausweisdaten - the master data itself never crosses.
 *
 * Pure business logic; self-description lives in [IdentFscDescriptor] (DPoP-demo-vun).
 * Delegates field-merging and the ready-to-verify decision to [IdentFscFlow].
 */
@Component
class IdentFscToolHandler(
    private val descriptor: IdentFscDescriptor,
    private val repository: IdFscToolDataRepository,
    private val fscCodeRepository: FscCodeRepository,
    private val personDirectory: PersonDirectory
) {

    /** Called directly by IdentFscToolController; nothing needs resolving before this can start. */
    @Transactional
    fun start(toolSessionId: UUID): ToolOutcome {
        repository.save(IdFscToolData(toolSessionId = toolSessionId))
        return outcomeFor(IdentFscState())
    }

    /**
     * [throttled] folds into the ordinary "code invalid" answer rather than getting an error of
     * its own - a distinguishable lock response would turn this into a KVNR-existence oracle.
     */
    @Transactional
    fun patch(
        toolSessionId: UUID,
        kvnr: String?,
        name: String?,
        vorname: String?,
        fsc: String?,
        personId: Long?,
        throttled: Boolean
    ): ToolOutcome {
        val data = checkNotNull(repository.findByIdOrNull(toolSessionId)) { "Unknown ident-fsc tool session: $toolSessionId" }

        val merged = IdentFscFlow.merge(data.toState(), IdentFscInput(kvnr, name, vorname, fsc, personId))
        data.applyState(merged)
        repository.save(data)

        return when (val decision = IdentFscFlow.decide(merged)) {
            IdentFscDecision.Incomplete -> outcomeFor(merged)

            IdentFscDecision.PersonNotFound -> ToolOutcome.Failed("Person zu dieser KVNR nicht gefunden")

            is IdentFscDecision.Verify -> {
                // The name is CHECKED, not merely collected: it used to be neither, so the two
                // fields the form insists on contributed nothing to the identification.
                val nameMatches = personDirectory.matchesName(decision.personId, decision.name, decision.vorname)
                val code = fscCodeRepository.findByPersonIdAndCodeHash(decision.personId, decision.fscHash)

                when (IdentFscFlow.decideVerification(throttled, nameMatches, code != null && code.isValid)) {
                    IdentFscVerifyDecision.Rejected ->
                        ToolOutcome.Failed("Freischaltcode ungueltig oder abgelaufen", attemptedPersonId = decision.personId)

                    IdentFscVerifyDecision.Complete -> ToolOutcome.Completed.Identified(
                        personId = decision.personId,
                        amr = listOf(descriptor.method),
                        achievedAcr = descriptor.maxAcr,
                        factorTypes = descriptor.factorTypes,
                        auditDetails = mapOf(
                            "provider" to "fsc-service",
                            "providerTxId" to "FSC-$toolSessionId",
                            "methodVersion" to "1.0",
                            "evidenceHash" to IdentFscFlow.evidenceHash(merged.kvnr.orEmpty(), decision.fscHash)
                        )
                    )
                }
            }
        }
    }

    @Transactional(readOnly = true)
    fun read(toolSessionId: UUID): ToolOutcome {
        val data = checkNotNull(repository.findByIdOrNull(toolSessionId)) { "Unknown ident-fsc tool session: $toolSessionId" }
        return outcomeFor(data.toState())
    }

    private fun outcomeFor(state: IdentFscState): ToolOutcome.InProgress {
        val (step, fields) = IdentFscFlow.describe(state)
        return ToolOutcome.InProgress(nextStep = step, data = fields)
    }

    private fun IdFscToolData.toState(): IdentFscState = IdentFscState(kvnr, name, vorname, fscHash, personId)

    private fun IdFscToolData.applyState(state: IdentFscState) {
        kvnr = state.kvnr
        name = state.name
        vorname = state.vorname
        fscHash = state.fscHash
        personId = state.personId
    }
}
