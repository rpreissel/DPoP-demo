package com.example.dpop.auth_sms.internal.authsmslookup

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Tool-session-scoped working data for toolId=auth-sms-lookup. [accountId] is only known once the
 * first PATCH (email) resolved it - unlike AuthSmsToolSession, which always knows the account
 * up front via the device-bound channel.
 */
@Entity
@Table(schema = "auth_sms", name = "lookup_tool_session")
class AuthSmsLookupToolSession(
    @Id
    @Column(name = "tool_session_id", nullable = false)
    var toolSessionId: UUID? = null,

    @Column(name = "account_id")
    var accountId: Long? = null,

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
