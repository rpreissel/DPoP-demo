package com.example.dpop.id_eid.internal

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Attempt-scoped module data for toolId=ident-eid. */
@Entity
@Table(name = "id_eid_tool_data")
class IdEidToolData(
    @Id
    @Column(name = "tool_session_id", nullable = false)
    var toolSessionId: UUID? = null,

    var kvnr: String? = null,

    @Column(name = "person_id")
    var personId: Long? = null,

    var name: String? = null,
    var vorname: String? = null,

    /** From here on: the simulated eID card's Ausweisdaten, read in the "card" step. */
    var geburtsdatum: LocalDate? = null,
    var strasse: String? = null,
    var hausnummer: String? = null,
    var plz: String? = null,
    var ort: String? = null,

    /** SHA-256 of the submitted PIN - the PIN itself is never persisted (V15). */
    @Column(name = "pin_hash")
    var pinHash: String? = null
) {
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant? = null

    init {
        createdAt = Instant.now()
    }
}
