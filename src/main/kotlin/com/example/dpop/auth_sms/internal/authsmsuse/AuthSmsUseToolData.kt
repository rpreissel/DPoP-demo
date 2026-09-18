package com.example.dpop.auth_sms.internal.authsmsuse

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** Tool-session-scoped working data for toolId=auth-sms (docs/06-ablaeufe.md #1). */
@Entity
@Table(schema = "auth_sms", name = "auth_tool_session")
class AuthSmsUseToolData(
    @Id
    @Column(name = "tool_session_id", nullable = false)
    var toolSessionId: UUID? = null,

    @Column(name = "enrollment_ref_type")
    var enrollmentRefType: String? = null,

    @Column(name = "enrollment_ref_id")
    var enrollmentRefId: String? = null,

    @Column(name = "issued_tan_hash")
    var issuedTanHash: String? = null,

    @Column(name = "tan_expires_at")
    var tanExpiresAt: Instant? = null
) {
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant? = null

    init {
        createdAt = Instant.now()
    }
}
