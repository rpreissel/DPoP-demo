package com.example.dpop.auth_qr.internal.enrollqr

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** Attempt-scoped module data for toolId=enroll-qr (docs/06-ablaeufe.md #1 pattern). */
@Entity
@Table(name = "enroll_qr_tool_data")
class EnrollQrToolData(
    @Id
    @Column(name = "tool_session_id", nullable = false)
    var toolSessionId: UUID? = null
) {
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant? = null

    init {
        createdAt = Instant.now()
    }
}
