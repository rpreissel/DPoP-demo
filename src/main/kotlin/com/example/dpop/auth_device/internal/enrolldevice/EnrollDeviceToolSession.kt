package com.example.dpop.auth_device.internal.enrolldevice

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** Tool-session-scoped working data for toolId=enroll-device (docs/06-ablaeufe.md pattern). */
@Entity
@Table(schema = "auth_device", name = "enroll_tool_session")
class EnrollDeviceToolSession(
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
