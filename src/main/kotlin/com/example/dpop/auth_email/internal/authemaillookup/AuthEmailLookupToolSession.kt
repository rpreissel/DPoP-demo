package com.example.dpop.auth_email.internal.authemaillookup

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Tool-session-scoped working data for toolId=auth-email-lookup. [accountId] is only known once the
 * first PATCH (email) resolved it - unlike AuthEmailUseToolSession, which never needs it at all
 * (email lives directly on Account, resolved once at the call site by the controller).
 */
@Entity
@Table(schema = "auth_email", name = "lookup_tool_session")
class AuthEmailLookupToolSession(
    @Id
    @Column(name = "tool_session_id", nullable = false)
    var toolSessionId: UUID? = null,

    @Column(name = "account_id")
    var accountId: Long? = null,

    @Column(name = "issued_code_hash")
    var issuedCodeHash: String? = null,

    @Column(name = "code_expires_at")
    var codeExpiresAt: Instant? = null
) {
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant? = null

    init {
        createdAt = Instant.now()
    }
}
