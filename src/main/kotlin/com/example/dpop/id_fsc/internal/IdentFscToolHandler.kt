package com.example.dpop.id_fsc.internal

import com.example.dpop.id_fsc.IdentFscDescriptor
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
 * (docs/08-projektrahmen.md #3: leaf modules stay decoupled from each other).
 *
 * Pure business logic; self-description lives in [IdentFscDescriptor] (DPoP-demo-vun).
 * `start(toolSessionId)` is called directly by IdentFscToolController, same as `patch`/`read` -
 * typed parameters throughout, no generic map (docs/08-projektrahmen.md A11).
 */
@Component
class IdentFscToolHandler(
    private val descriptor: IdentFscDescriptor,
    private val repository: IdFscToolDataRepository,
    private val fscCodeRepository: FscCodeRepository
) {

    /** Called directly by IdentFscToolController; nothing needs resolving before this can start. */
    @Transactional
    fun start(toolSessionId: UUID): ToolOutcome {
        repository.save(IdFscToolData(toolSessionId = toolSessionId))
        return ToolOutcome.InProgress(nextStep = "input", data = mapOf("missingFields" to REQUIRED_FIELDS))
    }

    @Transactional
    fun patch(toolSessionId: UUID, kvnr: String?, name: String?, vorname: String?, fsc: String?, personId: Long?): ToolOutcome {
        val data = checkNotNull(repository.findByIdOrNull(toolSessionId)) { "Unknown ident-fsc tool session: $toolSessionId" }

        kvnr?.let { data.kvnr = it }
        name?.let { data.name = it }
        vorname?.let { data.vorname = it }
        fsc?.let { data.fsc = it }
        personId?.let { data.personId = it }
        repository.save(data)

        val missing = missingFields(data)
        if (missing.isNotEmpty()) {
            return ToolOutcome.InProgress(nextStep = "input", data = mapOf("missingFields" to missing))
        }

        val resolvedPersonId = data.personId
            ?: return ToolOutcome.Failed("Person zu dieser KVNR nicht gefunden")
        val resolvedFsc = data.fsc.orEmpty()

        return if (verifyFsc(resolvedPersonId, resolvedFsc)) {
            ToolOutcome.Completed.Identified(
                personId = resolvedPersonId,
                amr = listOf(descriptor.method),
                achievedAcr = descriptor.maxAcr,
                factorTypes = descriptor.factorTypes,
                auditDetails = mapOf(
                    "provider" to "fsc-service",
                    "providerTxId" to "FSC-$toolSessionId",
                    "methodVersion" to "1.0",
                    "evidenceHash" to evidenceHash(data.kvnr.orEmpty(), resolvedFsc)
                )
            )
        } else {
            ToolOutcome.Failed("Freischaltcode ungueltig oder abgelaufen")
        }
    }

    @Transactional(readOnly = true)
    fun read(toolSessionId: UUID): ToolOutcome {
        val data = checkNotNull(repository.findByIdOrNull(toolSessionId)) { "Unknown ident-fsc tool session: $toolSessionId" }
        return ToolOutcome.InProgress(nextStep = "input", data = mapOf("missingFields" to missingFields(data)))
    }

    private fun verifyFsc(personId: Long, code: String): Boolean =
        fscCodeRepository.findByPersonIdAndCode(personId, code)?.isValid ?: false

    private fun missingFields(data: IdFscToolData): List<String> =
        listOfNotNull(
            "kvnr".takeIf { data.kvnr.isNullOrBlank() },
            "name".takeIf { data.name.isNullOrBlank() },
            "vorname".takeIf { data.vorname.isNullOrBlank() },
            "fsc".takeIf { data.fsc.isNullOrBlank() }
        )

    private fun evidenceHash(kvnr: String, fsc: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("$kvnr:$fsc".toByteArray())
        return "sha256:" + digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private val REQUIRED_FIELDS = listOf("kvnr", "name", "vorname")
    }
}
