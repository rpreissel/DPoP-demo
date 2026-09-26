package com.example.dpop.auth_email.internal.authemail

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Tool-session-scoped working data for toolId=auth-email. No enrollment-reference fields (unlike
 * auth_sms.auth_tool_session): the confirmed email is the account's EMAIL anchor, not a module-owned
 * enrollment row, so there is nothing to reference - the controller resolves the email string
 * once at activation and this module only needs to remember the issued code.
 */
@Entity
@Table(schema = "auth_email", name = "auth_tool_session")
class AuthEmailToolSession(
    @Id
    @Column(name = "tool_session_id", nullable = false)
    var toolSessionId: UUID? = null,

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
