package com.example.dpop.auth_password.internal.authpasswordlookup

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** Tool-session-scoped working data for toolId=auth-password-lookup - just an existence marker, single-step self-verifying tool. */
@Entity
@Table(schema = "auth_password", name = "lookup_tool_session")
class AuthPasswordLookupToolData(
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
