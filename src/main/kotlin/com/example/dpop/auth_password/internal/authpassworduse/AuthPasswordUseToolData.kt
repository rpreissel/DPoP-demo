package com.example.dpop.auth_password.internal.authpassworduse

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** Tool-session-scoped working data for toolId=auth-password (docs/06-ablaeufe.md #1 pattern). */
@Entity
@Table(schema = "auth_password", name = "auth_tool_session")
class AuthPasswordUseToolData(
    @Id
    @Column(name = "tool_session_id", nullable = false)
    var toolSessionId: UUID? = null,

    @Column(name = "enrollment_ref_type")
    var enrollmentRefType: String? = null,

    @Column(name = "enrollment_ref_id")
    var enrollmentRefId: String? = null
) {
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant? = null

    init {
        createdAt = Instant.now()
    }
}
