package com.example.dpop.id_eid.internal

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Tool-session-scoped working data for toolId=ident-eid. */
@Entity
@Table(schema = "id_eid", name = "ident_tool_session")
class IdEidToolSession(
    @Id
    @Column(name = "tool_session_id", nullable = false)
    var toolSessionId: UUID? = null,

    /** The simulated eID card's Ausweisdaten, read in one go in the "card" step. */
    var familyName: String? = null,
    var givenNames: String? = null,
    var birthDate: LocalDate? = null,
    var streetAddress: String? = null,
    var postalCode: String? = null,
    var locality: String? = null,

    /** The card's restricted identifier - person-unique pseudonym, ADR-19's recognition anchor. */
    @Column(name = "restricted_id", length = 64)
    var restrictedId: String? = null,

    /** SHA-256 of the submitted PIN - the PIN itself is never persisted. */
    @Column(name = "pin_hash")
    var pinHash: String? = null
) {
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant? = null

    init {
        createdAt = Instant.now()
    }
}
