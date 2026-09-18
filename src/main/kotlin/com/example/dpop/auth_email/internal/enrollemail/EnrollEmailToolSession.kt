package com.example.dpop.auth_email.internal.enrollemail

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Tool-session-scoped working data for toolId=enroll-email - and there is none beyond the row
 * itself, because activating the method proves nothing: control over the address was established
 * by `confirm-email`. Kept as a row anyway, like `auth_device`'s enroll data, so every tool run is
 * visible and ages out through the module's own retention sweep.
 */
@Entity
@Table(schema = "auth_email", name = "enroll_tool_session")
class EnrollEmailToolSession(
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
