package com.example.dpop.auth_password.internal

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** Attempt-scoped module data for toolId=auth-password (docs/06-ablaeufe.md #1 pattern). */
@Entity
@Table(name = "auth_password_use_tool_data")
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
