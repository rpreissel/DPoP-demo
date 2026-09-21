package com.example.dpop.auth_kobil.internal.authkobil

import com.example.dpop.tool_api.UserVerification
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** Tool-session-scoped working data for toolId=auth-kobil. */
@Entity
@Table(schema = "auth_kobil", name = "auth_tool_session")
class AuthKobilToolSession(
    @Id
    @Column(name = "tool_session_id", nullable = false)
    var toolSessionId: UUID? = null,

    @Column(name = "enrollment_ref_type")
    var enrollmentRefType: String? = null,

    @Column(name = "enrollment_ref_id")
    var enrollmentRefId: String? = null,
) {
    /**
     * Null until the PIN has been released; set, it names the access means that unlocked it.
     *
     * One nullable value instead of a released-flag beside a path name: "released but by nothing"
     * and "a path that never released anything" are then not states this table can be in. It is
     * also the single source for the amr entry the completed run reports - nothing has to be kept
     * in step with it.
     */
    @Column(name = "user_verification", length = 16)
    var userVerification: String? = null

    @Column(name = "pin_release_expires_at")
    var pinReleaseExpiresAt: Instant? = null

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now()

    /** The release, or null while there is none that still counts - an expired one is none. */
    fun liveRelease(now: Instant): UserVerification? {
        val expiry = pinReleaseExpiresAt ?: return null
        if (expiry.isBefore(now)) return null
        return UserVerification.fromWireValue(userVerification)
    }

    fun release(userVerification: UserVerification, expiresAt: Instant) {
        this.userVerification = userVerification.wireValue
        this.pinReleaseExpiresAt = expiresAt
    }
}
