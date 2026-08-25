package com.example.dpop.orchestrator.session

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * Account-level brute-force protection for AUTH-category tools (docs/04-orchestrierung.md).
 * ToolSession.retryCount alone is NOT enough: a client can always mint a fresh ToolSession via a
 * new POST .../tools/{toolId} call, resetting that counter - especially relevant once lookup-based login
 * (any device, no prior pairing) exists, where an attacker could otherwise keep guessing a
 * credential against one account indefinitely. Keyed by accountId, independent of any one
 * ChannelSession/ToolSession/device.
 */
@Entity
@Table(name = "login_attempt_throttle")
class LoginAttemptThrottle(
    @Id
    @Column(name = "account_id", nullable = false)
    var accountId: Long? = null
) {
    @Column(name = "failed_count", nullable = false)
    var failedCount: Int = 0

    @Column(name = "locked_until")
    var lockedUntil: Instant? = null

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant? = null

    init {
        updatedAt = Instant.now()
    }
}
