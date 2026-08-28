package com.example.dpop.id_eid.internal

import com.example.dpop.id_eid.IdentEidDescriptor
import com.example.dpop.tool_api.ClaimedIdentity
import com.example.dpop.tool_api.PersonDirectory
import com.example.dpop.tool_spi.ToolOutcome
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.util.UUID

/**
 * toolId=ident-eid. Resolves KVNR/name/vorname into a person, same seam as `ident-fsc`
 * (`IdentEidToolController` looks `personId` up via `PersonDirectory` since `id_eid` must not
 * depend on `ext_stammdaten` directly). Two further steps follow, each its own `nextStep` so the
 * client can show a distinct screen: a simulated eID card read that hands back the card's
 * Ausweisdaten (possession), then a PIN (knowledge) - mirroring the two factors a real eID run
 * proves in one go.
 *
 * Once all three are in, the handler asks `PersonDirectory` whether the stammdaten on file match
 * every Ausweisdaten attribute - ext_stammdaten owns that comparison itself and never hands the
 * master data back across the port. This only needs the entity's already-persisted `personId`,
 * not whatever a given PATCH call happened to resend, so it stays in the handler rather than the
 * controller (unlike the KVNR lookup, which must re-run on every PATCH that carries a `kvnr`).
 */
@Component
class IdentEidToolHandler(
    private val descriptor: IdentEidDescriptor,
    private val repository: IdEidToolDataRepository,
    private val personDirectory: PersonDirectory
) {

    /** Called directly by IdentEidToolController; nothing needs resolving before this can start. */
    @Transactional
    fun start(toolSessionId: UUID): ToolOutcome {
        repository.save(IdEidToolData(toolSessionId = toolSessionId))
        return ToolOutcome.InProgress(nextStep = "input", data = mapOf("missingFields" to LOOKUP_FIELDS))
    }

    /**
     * [throttled] folds into the ordinary "PIN invalid" answer rather than getting an error of
     * its own - a distinguishable lock response would turn this into a KVNR-existence oracle.
     */
    @Transactional
    fun patch(toolSessionId: UUID, fields: EidPatchFields, personId: Long?, throttled: Boolean): ToolOutcome {
        val data = checkNotNull(repository.findByIdOrNull(toolSessionId)) { "Unknown ident-eid tool session: $toolSessionId" }

        fields.kvnr?.let { data.kvnr = it }
        fields.name?.let { data.name = it }
        fields.vorname?.let { data.vorname = it }
        personId?.let { data.personId = it }
        fields.geburtsdatum?.let { data.geburtsdatum = it }
        fields.strasse?.let { data.strasse = it }
        fields.hausnummer?.let { data.hausnummer = it }
        fields.plz?.let { data.plz = it }
        fields.ort?.let { data.ort = it }
        fields.pin?.let { data.pinHash = hash(it.trim()) }
        repository.save(data)

        if (data.kvnr.isNullOrBlank() || data.name.isNullOrBlank() || data.vorname.isNullOrBlank()) {
            return ToolOutcome.InProgress(nextStep = "input", data = mapOf("missingFields" to LOOKUP_FIELDS))
        }
        if (data.geburtsdatum == null || data.strasse.isNullOrBlank() || data.hausnummer.isNullOrBlank() ||
            data.plz.isNullOrBlank() || data.ort.isNullOrBlank()
        ) {
            return ToolOutcome.InProgress(nextStep = "card", data = mapOf("missingFields" to CARD_FIELDS))
        }
        if (data.pinHash.isNullOrBlank()) {
            return ToolOutcome.InProgress(nextStep = "pin", data = mapOf("missingFields" to PIN_FIELDS))
        }

        val resolvedPersonId = data.personId
            ?: return ToolOutcome.Failed("Person zu dieser KVNR nicht gefunden")

        // Constant-time, and against the stored hash - the PIN itself is no longer persisted.
        if (throttled || !MessageDigest.isEqual(data.pinHash?.toByteArray(), hash(MOCK_PIN).toByteArray())) {
            return ToolOutcome.Failed("eID-PIN ungueltig", attemptedPersonId = resolvedPersonId)
        }

        val claimed = ClaimedIdentity(
            name = data.name.orEmpty(),
            vorname = data.vorname.orEmpty(),
            geburtsdatum = checkNotNull(data.geburtsdatum),
            strasse = data.strasse.orEmpty(),
            hausnummer = data.hausnummer.orEmpty(),
            plz = data.plz.orEmpty(),
            ort = data.ort.orEmpty()
        )
        if (!personDirectory.matchesStammdaten(resolvedPersonId, claimed)) {
            return ToolOutcome.Failed(
                "Ausweisdaten stimmen nicht mit den angegebenen Daten ueberein",
                attemptedPersonId = resolvedPersonId
            )
        }

        val documentNumber = mockDocumentNumber(toolSessionId)
        return ToolOutcome.Completed.Identified(
            personId = resolvedPersonId,
            amr = listOf(descriptor.method),
            achievedAcr = descriptor.maxAcr,
            factorTypes = descriptor.factorTypes,
            auditDetails = mapOf(
                "provider" to "eid-mock-service",
                "providerTxId" to "EID-$toolSessionId",
                "methodVersion" to "1.0",
                "documentNumber" to documentNumber,
                "evidenceHash" to evidenceHash(data.kvnr.orEmpty(), data.pinHash.orEmpty(), documentNumber)
            )
        )
    }

    @Transactional(readOnly = true)
    fun read(toolSessionId: UUID): ToolOutcome {
        val data = checkNotNull(repository.findByIdOrNull(toolSessionId)) { "Unknown ident-eid tool session: $toolSessionId" }
        return when {
            data.kvnr.isNullOrBlank() || data.name.isNullOrBlank() || data.vorname.isNullOrBlank() ->
                ToolOutcome.InProgress(nextStep = "input", data = mapOf("missingFields" to LOOKUP_FIELDS))
            data.geburtsdatum == null || data.strasse.isNullOrBlank() || data.hausnummer.isNullOrBlank() ||
                data.plz.isNullOrBlank() || data.ort.isNullOrBlank() ->
                ToolOutcome.InProgress(nextStep = "card", data = mapOf("missingFields" to CARD_FIELDS))
            else ->
                ToolOutcome.InProgress(nextStep = "pin", data = mapOf("missingFields" to PIN_FIELDS))
        }
    }

    private fun mockDocumentNumber(toolSessionId: UUID): String =
        "MOCK" + toolSessionId.toString().replace("-", "").take(9).uppercase()

    private fun evidenceHash(kvnr: String, pinHash: String, documentNumber: String): String =
        "sha256:" + hash("$kvnr:$pinHash:$documentNumber")

    private fun hash(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    companion object {
        private val LOOKUP_FIELDS = listOf("kvnr", "name", "vorname")
        private val CARD_FIELDS = listOf("geburtsdatum", "strasse", "hausnummer", "plz", "ort")
        private val PIN_FIELDS = listOf("pin")

        /** Fixed test PIN for the mock, same role as `ident-fsc`'s `VALIDCODE` (docs/08-projektrahmen.md P-5/P-6). */
        const val MOCK_PIN = "123456"
    }
}
