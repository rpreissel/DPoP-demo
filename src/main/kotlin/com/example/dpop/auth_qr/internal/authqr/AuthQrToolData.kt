package com.example.dpop.auth_qr.internal.authqr

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** Attempt-scoped module data for toolId=auth-qr - just which QrLoginRequest this session waits on. */
@Entity
@Table(name = "auth_qr_tool_data")
class AuthQrToolData(
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
