package com.example.dpop.auth_sms.internal

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Attempt-scoped module data for toolId=auth-sms-lookup. [accountId] is only known once the
 * first PATCH (email) resolved it - unlike AuthSmsUseToolData, which always knows the account
 * up front via the device-bound channel.
 */
@Entity
@Table(name = "auth_sms_lookup_tool_data")
class AuthSmsLookupToolData(
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
