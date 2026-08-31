package com.example.dpop.orchestrator.journeylog

import com.example.dpop.orchestrator.journey.AuthIntent
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
 * Rich, per-step trace of a journey's path (docs/04-orchestrierung.md), distinct from
 * [com.example.dpop.orchestrator.session.SessionEvent]: that one is a minimized audit trail
 * (hashed payloads), this one exists to make a journey's actual path reconstructable for
 * debugging/demo purposes - not a replacement, a different tradeoff.
 */
@Entity
@Table(name = "journey_log")
class JourneyLogEntry(
    @Column(name = "binding_key_ref", nullable = false, length = 64)
    var bindingKeyRef: String? = null,

    @Column(name = "channel_session_id", nullable = false)
    var channelSessionId: UUID? = null,

    /** Null for a channel-level event with no journey of its own (e.g. logout with nothing running) - see [JourneyLogService.recordForChannel]. */
    @Column(name = "journey_id")
    var journeyId: UUID? = null,

    /** Set when this journey ran as another journey's precondition (docs/04-orchestrierung.md #6) - lets the log nest a sub-journey's steps under the journey that required it, instead of showing it as an unrelated journey. */
    @Column(name = "parent_journey_id")
    var parentJourneyId: UUID? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "intent", length = 20)
    var intent: AuthIntent? = null,

    @Column(name = "event_type", nullable = false, length = 50)
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
    @Column(name = "log_id", nullable = false)
    var logId: UUID? = null

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant? = null

    init {
        createdAt = Instant.now()
    }
}
