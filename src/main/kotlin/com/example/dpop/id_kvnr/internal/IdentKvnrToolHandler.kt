package com.example.dpop.id_kvnr.internal

import com.example.dpop.tool_api.PersonDirectory
import com.example.dpop.texts.Text
import com.example.dpop.id_kvnr.IdentKvnrDescriptor
import com.example.dpop.tool_spi.AttributeType
import com.example.dpop.tool_spi.Claim
import com.example.dpop.tool_spi.ClaimSource
import com.example.dpop.tool_spi.ToolOutcome
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import com.example.dpop.tool_spi.MissingFields

/**
 * toolId=ident-kvnr. Takes the one value an attestation cannot carry - the Versichertennummer,
 * or for a Partner without one the Partnernummer (ADR-34) - and turns it into the register's own
 * person reference (docs/12-entscheidungen.md ADR-18).
 *
 * It proves nothing on its own and is never a standalone identification: it only runs on top of
 * an already attested identity ([IdentKvnrDescriptor.requires]), and the account module checks
 * that the register's person actually matches what was attested before any anchor is written.
 * This tool never sees accounts.
 *
 * Pure business logic; self-description lives in [IdentKvnrDescriptor].
 */
@Component
class IdentKvnrToolHandler(
    private val descriptor: IdentKvnrDescriptor,
    private val repository: IdKvnrToolSessionRepository,
    private val personDirectory: PersonDirectory
) {

    @Transactional
    fun start(toolSessionId: UUID): ToolOutcome {
        repository.save(IdKvnrToolSession(toolSessionId = toolSessionId))
        return inProgress()
    }

    /**
     * [personId] is resolved by the controller via `PersonDirectory`, since `id_kvnr` must not
     * depend on `ext_personenverzeichnis` directly - same seam as `ident-fsc`.
     *
     * The KVNR comes first: given, [partnernr] is ignored - the controller resolved by it alone.
     *
     * An unknown KVNR answers exactly like one that resolves to somebody else's person: the
     * message never distinguishes the two, so this cannot be used to probe which numbers exist
     * (the same reasoning that folds a throttle lock into `ident-fsc`'s ordinary failure).
     */
    @Transactional
    fun patch(toolSessionId: UUID, kvnr: String?, partnernr: String?, personId: String?): ToolOutcome {
        val data = checkNotNull(repository.findByIdOrNull(toolSessionId)) { "Unknown ident-kvnr tool session: $toolSessionId" }
        val byKvnr = !kvnr.isNullOrBlank()
        if (!byKvnr && partnernr.isNullOrBlank()) return inProgress()
        if (byKvnr) data.kvnr = kvnr else data.partnernr = partnernr
        repository.save(data)

        personId ?: return ToolOutcome.Failed(
            if (byKvnr) Text("Versichertennummer konnte nicht zugeordnet werden") else Text("Partnernummer konnte nicht zugeordnet werden")
        )

        return ToolOutcome.Completed.Identified(
            amr = listOf(descriptor.method),
            achievedAcr = descriptor.maxAcr,
            factorTypes = descriptor.factorTypes,
            claims = listOfNotNull(
                Claim(AttributeType.PERSON_ID, personId, ClaimSource.PERSON_DIRECTORY, descriptor.maxAcr),
                kvnr?.takeIf { it.isNotBlank() }?.let { Claim(AttributeType.KVNR, it, ClaimSource.PERSON_DIRECTORY, descriptor.maxAcr) },
                // Insured with us: the Versicherungsnummer becomes an anchor too (ADR-34).
                personDirectory.versnrOf(personId)?.let { Claim(AttributeType.VERSNR, it, ClaimSource.PERSON_DIRECTORY, descriptor.maxAcr) }
            ),
            auditDetails = mapOf("methodVersion" to "1.0")
        )
    }

    @Transactional(readOnly = true)
    fun read(toolSessionId: UUID): ToolOutcome {
        checkNotNull(repository.findByIdOrNull(toolSessionId)) { "Unknown ident-kvnr tool session: $toolSessionId" }
        return inProgress()
    }

    private fun inProgress() = ToolOutcome.InProgress(nextStep = "input", stepData = MissingFields(listOf("kvnr")))
}
