package com.example.dpop.orchestrator.journeylog

import com.example.dpop.orchestrator.kernel.AuthIntent
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

/**
 * Rich, per-step trace of a journey's path (docs/04-orchestrierung.md) for debugging, support and
 * the demo - kept 14 days. Not the audit trail: that is the account's value-free
 * `account.audit_event` (ADR-39), which outlives even the account. A different tradeoff, not a copy.
 */
@Entity
@Table(schema = "orchestrator", name = "journey_log")
class JourneyLogEntry(
    /** APP-only - null for WEB-channel entries, which have no DPoP binding key (docs/02-domaenenmodell.md Abschnitt 1). */
    @Column(name = "binding_key_ref", length = 64)
    var bindingKeyRef: String? = null,

    /** Persisted source of the log entry itself - APP or KEYCLOAK - so the channel remains visible even after the session is gone. */
    @Column(name = "channel_type", length = 32)
    var channelType: String? = null,

    /** Null until the channel resolves an account (e.g. before identification) - the lookup key every facade's entries share once one is known, unlike [bindingKeyRef] which is APP-only. */
    @Column(name = "account_id")
    var accountId: Long? = null,

    @Column(name = "channel_session_id", nullable = false)
    var channelSessionId: UUID? = null,

    /** Null for a channel-level event with no journey of its own (e.g. logout with nothing running) - see [JourneyLogService.recordForChannel]. */
    @Column(name = "journey_id")
    var journeyId: UUID? = null,

    /** Set when this journey ran as another journey's precondition (docs/04-orchestrierung.md #6) - lets the log nest a sub-journey's steps under the journey that required it, instead of showing it as an unrelated journey. */
    @Column(name = "parent_journey_id")
    var parentJourneyId: UUID? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "intent", length = 32)
    var intent: AuthIntent? = null,

    @Column(name = "event_type", nullable = false, length = 100)
    var eventType: String? = null,

    /** The JourneyState subtype the journey was in when this event happened (e.g. "AwaitingTan") - null for a channel-level event with no journey. */
    @Column(name = "journey_state", length = 100)
    var journeyState: String? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detail")
    var detail: Map<String, Any?>? = null
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    var logId: UUID? = null

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant? = null

    init {
        createdAt = Instant.now()
    }
}
