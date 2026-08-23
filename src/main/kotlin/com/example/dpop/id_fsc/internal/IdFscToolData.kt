package com.example.dpop.id_fsc.internal

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** Attempt-scoped module data for toolId=ident-fsc (docs/06-ablaeufe.md #1). */
@Entity
@Table(name = "id_fsc_tool_data")
class IdFscToolData(
    @Id
    @Column(name = "tool_session_id", nullable = false)
    var toolSessionId: UUID? = null,

    var kvnr: String? = null,

    @Column(name = "person_id")
    var personId: Long? = null,

    var name: String? = null,
    var vorname: String? = null,
    var fsc: String? = null
) {
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant? = null

    init {
        createdAt = Instant.now()
    }
}
