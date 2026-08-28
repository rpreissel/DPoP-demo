package com.example.dpop.orchestrator.session

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.io.Serializable
import java.time.Instant

/**
 * What a throttled subject IS. Part of the primary key, so the three counting spaces can never
 * collide - accountId 42 and personId 42 are different subjects, and a String subject column
 * alone would silently merge them.
 */
enum class ThrottleScope {
    /** AUTH attempts against one account (docs/04-orchestrierung.md). */
    ACCOUNT,

    /**
     * IDENT attempts against one person. Deliberately NOT folded into [ACCOUNT]: an
     * identification runs before any account is known (and may create one), so there is no
     * accountId to key on - yet `ident-fsc` guesses exactly one secret and its success is a full
     * account adoption via `findOrCreateAccount`.
     */
    PERSON,

    /**
     * Channel creations per DPoP binding key. The other two only bound attempts WITHIN a journey
     * chain; this one bounds how cheaply an attacker can mint fresh chains in the first place.
     */
    BINDING_KEY
}

@Embeddable
data class AttemptThrottleId(
    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, length = 20)
    var scope: ThrottleScope? = null,

    @Column(name = "subject", nullable = false, length = 128)
    var subject: String? = null
) : Serializable

/**
 * Brute-force counter for one (scope, subject) pair. Replaces the account-only
 * `login_attempt_throttle`: a per-ToolSession or per-journey counter cannot bound anything a
 * client can simply restart, and before this the whole lookup-login path (where no accountId is
 * known until AFTER the credential check succeeds) was counted by nothing at all.
 *
 * Pure data. The counting rules live in the three named services over it
 * ([LoginThrottleService], [IdentThrottleService], [ChannelCreationThrottleService]) - each keeps
 * its own vocabulary and its own limits rather than sharing one generic entry point.
 */
@Entity
@Table(name = "attempt_throttle")
class AttemptThrottle(
    @EmbeddedId
    var id: AttemptThrottleId? = null
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
