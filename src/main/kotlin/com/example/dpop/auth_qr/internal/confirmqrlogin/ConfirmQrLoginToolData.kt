package com.example.dpop.auth_qr.internal.confirmqrlogin

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Attempt-scoped module data for toolId=confirm-qr-login. [pairingCode] is `null` until the
 * `input` step resolves a valid one - that transition from `null` to set is what moves this tool
 * from `input` to `confirm` (docs/05-api.md, Peer-Login bestätigen).
 */
@Entity
@Table(name = "confirm_qr_login_tool_data")
class ConfirmQrLoginToolData(
    @Id
    @Column(name = "tool_session_id", nullable = false)
    var toolSessionId: UUID? = null
) {
    @Column(name = "pairing_code")
    var pairingCode: String? = null

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant? = null

    init {
        createdAt = Instant.now()
    }
}
