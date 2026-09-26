package com.example.dpop.id_fsc.internal

import com.example.dpop.texts.Text
import com.example.dpop.ext_personenverzeichnis.Freischaltcodes
import com.example.dpop.id_fsc.IdentFscDescriptor
import com.example.dpop.tool_api.PersonDirectory
import com.example.dpop.tool_spi.AttributeType
import com.example.dpop.tool_spi.Claim
import com.example.dpop.tool_spi.ToolOutcome
import com.example.dpop.tool_spi.ClaimSource
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.util.UUID

/**
 * toolId=ident-fsc (docs/06-ablaeufe.md #2). Resolves KVNR (or, for a Partner without one, the
 * Partnernummer - ADR-34)/name/vorname/geburtsdatum/FSC into a person -
 * that resolution *is* the module's contribution, not just a yes/no check.
 *
 * [patch]'s [personId] parameter arrives pre-resolved: IdentFscToolController looks it up over the
 * [PersonDirectory] port when a kvnr is supplied, and the name/birthdate check goes back out over the same
 * port, exactly as `ident-eid` verifies its Ausweisdaten - the master data itself never crosses.
 * The code check alone asks the register directly ([Freischaltcodes], ADR-31): the register issued
 * the code, so it is the one to say whether it is valid - the same edge `auth_kobil` has to
 * `kobil_mock`.
 *
 * Pure business logic; self-description lives in [IdentFscDescriptor].
 * Delegates field-merging and the ready-to-verify decision to [IdentFscFlow].
 */
private val PERSONALIEN_REJECTED = Text("Die Angaben passen zu keiner Person, die wir kennen")

@Component
class IdentFscToolHandler(
    private val descriptor: IdentFscDescriptor,
    private val repository: IdFscToolSessionRepository,
    private val freischaltcodes: Freischaltcodes,
    private val personDirectory: PersonDirectory
) {

    /** Called directly by IdentFscToolController; nothing needs resolving before this can start. */
    @Transactional
    fun start(toolSessionId: UUID): ToolOutcome {
        repository.save(IdFscToolSession(toolSessionId = toolSessionId))
        return outcomeFor(IdentFscState())
    }

    /**
     * [throttled] guards the code, the secret that can be guessed: it folds into the ordinary
     * "code invalid" answer rather than getting an error of its own - a distinguishable lock
     * response would turn this into a KVNR-existence oracle. A rejected personal-data check still
     * charges the person's counter (attemptedPersonId), so probing it runs into the same lock.
     */
    @Transactional
    fun patch(
        toolSessionId: UUID,
        kvnr: String?,
        partnernr: String?,
        name: String?,
        vorname: String?,
        geburtsdatum: LocalDate?,
        fsc: String?,
        personId: String?,
        throttled: Boolean
    ): ToolOutcome {
        val data = checkNotNull(repository.findByIdOrNull(toolSessionId)) { "Unknown ident-fsc tool session: $toolSessionId" }

        val input = IdentFscInput(kvnr, partnernr, name, vorname, geburtsdatum, fsc, personId)
        val merged = IdentFscFlow.merge(data.toState(), input)

        // Every personal-data rejection answers alike, whether the KVNR is unknown or a
        // name/birthdate is off - anything finer would tell a caller which part was wrong.
        val outcome = when (val decision = IdentFscFlow.decide(merged, input)) {
            IdentFscDecision.Incomplete -> merged to outcomeFor(merged)

            IdentFscDecision.PersonNotFound ->
                IdentFscFlow.rejectPersonalien() to ToolOutcome.Failed.Identification(PERSONALIEN_REJECTED, attemptedPersonId = null)

            is IdentFscDecision.VerifyPersonalien -> {
                // Name and birthdate are CHECKED, not merely collected - and before the code is
                // even asked for, so nobody types a code for data that could never match.
                val matches = personDirectory.matchesPersonalien(
                    decision.personId, decision.name, decision.vorname, decision.geburtsdatum
                )
                when {
                    !matches -> IdentFscFlow.rejectPersonalien() to
                        ToolOutcome.Failed.Identification(PERSONALIEN_REJECTED, attemptedPersonId = decision.personId)
                    // All five in one PATCH: the personal data holds, so the code is next.
                    merged.fscHash != null -> verifyCode(toolSessionId, merged, decision.personId, merged.fscHash, throttled)
                    else -> merged to outcomeFor(merged)
                }
            }

            is IdentFscDecision.VerifyCode -> verifyCode(toolSessionId, merged, decision.personId, decision.fscHash, throttled)
        }

        data.applyState(outcome.first)
        repository.save(data)
        return outcome.second
    }

    private fun verifyCode(
        toolSessionId: UUID,
        state: IdentFscState,
        personId: String,
        fscHash: String,
        throttled: Boolean
    ): Pair<IdentFscState, ToolOutcome> {
        if (throttled || !freischaltcodes.pruefe(personId, fscHash)) {
            return IdentFscFlow.rejectCode(state) to
                ToolOutcome.Failed.Identification(Text("Freischaltcode ungueltig oder abgelaufen"), attemptedPersonId = personId)
        }
        return state to ToolOutcome.Completed.Identified(
            amr = listOf(descriptor.method),
            achievedAcr = descriptor.maxAcr,
            factorTypes = descriptor.factorTypes,
            claims = listOfNotNull(
                // FSC is a master-data channel: every attribute this run asserts
                // was checked against ext_personenverzeichnis, hence PERSON_DIRECTORY as the
                // trust anchor, not this tool's own id.
                Claim(AttributeType.PERSON_ID, personId, ClaimSource.PERSON_DIRECTORY, descriptor.maxAcr),
                // A Partner identifies by Partnernummer and has no KVNR (ADR-34).
                state.kvnr?.let { Claim(AttributeType.KVNR, it, ClaimSource.PERSON_DIRECTORY, descriptor.maxAcr) },
                Claim(AttributeType.NAME, checkNotNull(state.name), ClaimSource.PERSON_DIRECTORY, descriptor.maxAcr),
                Claim(AttributeType.VORNAME, checkNotNull(state.vorname), ClaimSource.PERSON_DIRECTORY, descriptor.maxAcr),
                // Checked against the register like the name (matchesPersonalien) - and one of the
                // three things that find this identification in the change log (ADR-39).
                Claim(AttributeType.GEBURTSDATUM, checkNotNull(state.geburtsdatum).toString(), ClaimSource.PERSON_DIRECTORY, descriptor.maxAcr),
                // Insured with us: the Versicherungsnummer becomes an anchor too (ADR-34).
                personDirectory.versnrOf(personId)?.let { Claim(AttributeType.VERSNR, it, ClaimSource.PERSON_DIRECTORY, descriptor.maxAcr) }
            ),
            auditDetails = mapOf(
                "provider" to "fsc-service",
                "providerTxId" to "FSC-$toolSessionId",
                "methodVersion" to "1.0",
                "evidenceHash" to IdentFscFlow.evidenceHash(state.kvnr ?: state.partnernr.orEmpty(), fscHash)
            )
        )
    }

    @Transactional(readOnly = true)
    fun read(toolSessionId: UUID): ToolOutcome {
        val data = checkNotNull(repository.findByIdOrNull(toolSessionId)) { "Unknown ident-fsc tool session: $toolSessionId" }
        return outcomeFor(data.toState())
    }

    private fun outcomeFor(state: IdentFscState): ToolOutcome.InProgress {
        val (step, fields) = IdentFscFlow.describe(state)
        return ToolOutcome.InProgress(nextStep = step, stepData = fields)
    }

    private fun IdFscToolSession.toState(): IdentFscState = IdentFscState(kvnr, partnernr, name, vorname, geburtsdatum, fscHash, personId)

    private fun IdFscToolSession.applyState(state: IdentFscState) {
        kvnr = state.kvnr
        partnernr = state.partnernr
        name = state.name
        vorname = state.vorname
        geburtsdatum = state.geburtsdatum
        fscHash = state.fscHash
        personId = state.personId
    }
}
