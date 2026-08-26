package com.example.dpop.orchestrator.journey

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

/**
 * One run of one [AuthIntent]: a guided path with a goal (docs/04-orchestrierung.md #1). Belongs
 * to exactly one ChannelSession and lives shorter than it; at most one journey per channel is
 * [JourneyLifecycle.STARTED].
 *
 * Pure data, no behaviour and no inheritance: what to do next needs services (AuthPolicy,
 * AccountService, the tool catalog) that a JPA entity must not hold, so that lives in an
 * [IntentStrategy] bean per intent instead.
 *
 * Deliberately absent: any `next*` routing column. `next` is derived from [state]
 * ([JourneyMachine.nextFor]) - storing it as well would be a second copy of the same truth, free
 * to drift.
 */
@Entity
@Table(name = "auth_journey")
class AuthJourney(
    @Column(name = "channel_session_id", nullable = false)
    var channelSessionId: UUID? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "intent", nullable = false, length = 20)
    var intent: AuthIntent? = null,

    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant? = null
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "journey_id", nullable = false)
    var journeyId: UUID? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "lifecycle", nullable = false, length = 20)
    var lifecycle: JourneyLifecycle = JourneyLifecycle.STARTED

    @Column(name = "account_id")
    var accountId: Long? = null

    /** Discriminator of [state], kept as its own column so journeys stay queryable by position. */
    @Column(name = "state_type", nullable = false, length = 50)
    var stateType: String? = null

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "state", nullable = false)
    var state: String? = null

    /**
     * Attempts left across the WHOLE journey, not per tool. A tool-local counter cannot cover
     * this: once an exhausted state moves on instead of terminating, brute force over the ladder
     * gets cheaper (docs/04-orchestrierung.md #7).
     */
    @Column(name = "attempt_budget", nullable = false)
    var attemptBudget: Int = DEFAULT_ATTEMPT_BUDGET

    /** Set on a journey started as another one's precondition; that parent is SUSPENDED meanwhile. */
    @Column(name = "parent_journey_id")
    var parentJourneyId: UUID? = null

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant? = null

    @Column(name = "consumed_at")
    var consumedAt: Instant? = null

    @Version
    @Column(name = "version", nullable = false)
    var version: Long? = null

    init {
        createdAt = Instant.now()
    }

    fun consume() {
        consumedAt = Instant.now()
        lifecycle = JourneyLifecycle.CONSUMED
    }

    fun fail() {
        lifecycle = JourneyLifecycle.FAILED
    }

    /** User-initiated abandonment, distinct from [fail] (budget exhausted). */
    fun cancel() {
        lifecycle = JourneyLifecycle.CANCELLED
    }

    val isExpired: Boolean
        get() = expiresAt?.let { Instant.now().isAfter(it) } ?: false

    companion object {
        const val DEFAULT_ATTEMPT_BUDGET = 3
    }
}
