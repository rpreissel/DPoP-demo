package com.example.dpop.id_fsc.internal

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Tool-session-scoped working data for toolId=ident-fsc (docs/06-ablaeufe.md #1). */
@Entity
@Table(schema = "id_fsc", name = "ident_tool_session")
class IdFscToolSession(
    @Id
    @Column(name = "tool_session_id", nullable = false)
    var toolSessionId: UUID? = null,

    var kvnr: String? = null,

    var partnernr: String? = null,

    @Column(name = "person_id")
    var personId: String? = null,

    var name: String? = null,
    var vorname: String? = null,
    var geburtsdatum: LocalDate? = null,
    /** SHA-256 of the submitted code - the code itself is never persisted. */
    @Column(name = "fsc_hash")
    var fscHash: String? = null
) {
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant? = null

    init {
        createdAt = Instant.now()
    }
}
