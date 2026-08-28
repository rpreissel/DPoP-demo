package com.example.dpop.id_fsc.internal

import com.example.dpop.id_fsc.IdentFscDescriptor
import com.example.dpop.tool_api.PersonDirectory
import com.example.dpop.tool_spi.ToolOutcome
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
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
 * `start(toolSessionId)` is called directly by IdentFscToolController, same as `patch`/`read` -
 * typed parameters throughout, no generic map (docs/08-projektrahmen.md A11).
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
        return ToolOutcome.InProgress(nextStep = "input", data = mapOf("missingFields" to REQUIRED_FIELDS))
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

        kvnr?.let { data.kvnr = it }
        name?.let { data.name = it }
        vorname?.let { data.vorname = it }
        fsc?.let { data.fscHash = hash(it.trim()) }
        personId?.let { data.personId = it }
        repository.save(data)

        val missing = missingFields(data)
        if (missing.isNotEmpty()) {
            return ToolOutcome.InProgress(nextStep = "input", data = mapOf("missingFields" to missing))
        }

        val resolvedPersonId = data.personId
            ?: return ToolOutcome.Failed("Person zu dieser KVNR nicht gefunden")
        val submittedHash = checkNotNull(data.fscHash) { "fsc marked present but no hash stored" }

        // The name is CHECKED, not merely collected. It used to be neither: personId came from
        // the KVNR alone and name/vorname were stored and never compared to anything, so the two
        // fields the form insists on contributed nothing to the identification.
        val nameMatches = personDirectory.matchesName(resolvedPersonId, data.name.orEmpty(), data.vorname.orEmpty())
        val code = fscCodeRepository.findByPersonIdAndCodeHash(resolvedPersonId, submittedHash)

        if (throttled || !nameMatches || code == null || !code.isValid) {
            return ToolOutcome.Failed(
                "Freischaltcode ungueltig oder abgelaufen",
                attemptedPersonId = resolvedPersonId
            )
        }

        return ToolOutcome.Completed.Identified(
            personId = resolvedPersonId,
            amr = listOf(descriptor.method),
            achievedAcr = descriptor.maxAcr,
            factorTypes = descriptor.factorTypes,
            auditDetails = mapOf(
                "provider" to "fsc-service",
                "providerTxId" to "FSC-$toolSessionId",
                "methodVersion" to "1.0",
                "evidenceHash" to "sha256:" + hash("${data.kvnr.orEmpty()}:$submittedHash")
            )
        )
    }

    @Transactional(readOnly = true)
    fun read(toolSessionId: UUID): ToolOutcome {
        val data = checkNotNull(repository.findByIdOrNull(toolSessionId)) { "Unknown ident-fsc tool session: $toolSessionId" }
        return ToolOutcome.InProgress(nextStep = "input", data = mapOf("missingFields" to missingFields(data)))
    }

    private fun missingFields(data: IdFscToolData): List<String> =
        listOfNotNull(
            "kvnr".takeIf { data.kvnr.isNullOrBlank() },
            "name".takeIf { data.name.isNullOrBlank() },
            "vorname".takeIf { data.vorname.isNullOrBlank() },
            "fsc".takeIf { data.fscHash.isNullOrBlank() }
        )

    private fun hash(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    companion object {
        /**
         * The FIRST stage only - `fsc` is deliberately absent, the same staging `ident-eid` uses
         * (input -> card -> pin): the code is asked for once the person is named. [missingFields]
         * adds it from then on.
         */
        private val REQUIRED_FIELDS = listOf("kvnr", "name", "vorname")
    }
}
