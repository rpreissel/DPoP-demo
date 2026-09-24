package com.example.dpop.id_kvnr.internal

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** Tool-session-scoped working data for toolId=ident-kvnr - what was typed, kept for the same resume-after-reload reason every other tool session exists. */
@Entity
@Table(schema = "id_kvnr", name = "ident_tool_session")
class IdKvnrToolSession(
    @Id
    @Column(name = "tool_session_id", nullable = false)
    var toolSessionId: UUID? = null,

    var kvnr: String? = null,

    var partnernr: String? = null
) {
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant? = null

    init {
        createdAt = Instant.now()
    }
}
