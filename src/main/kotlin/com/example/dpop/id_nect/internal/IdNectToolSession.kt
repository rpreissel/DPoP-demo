package com.example.dpop.id_nect.internal

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** Tool-session-scoped working data for toolId=ident-nect: the Nect case this run waits for. */
@Entity
@Table(schema = "id_nect", name = "ident_tool_session")
class IdNectToolSession(
    @Id
    @Column(name = "tool_session_id", nullable = false)
    var toolSessionId: UUID? = null,

    @Column(name = "case_id")
    var caseId: UUID? = null
) {
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant? = null

    init {
        createdAt = Instant.now()
    }
}
