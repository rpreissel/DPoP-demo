package com.example.dpop.auth_qr.internal.authqrlookup

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** Tool-session-scoped working data for toolId=auth-qr-lookup - just which QrLoginRequest this session waits on. */
@Entity
@Table(schema = "auth_qr", name = "lookup_tool_session")
class AuthQrLookupToolSession(
    @Id
    @Column(name = "tool_session_id", nullable = false)
    var toolSessionId: UUID? = null,

    @Column(name = "pairing_code", nullable = false)
    var pairingCode: String? = null
) {
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant? = null

    init {
        createdAt = Instant.now()
    }
}
