package com.example.dpop.orchestrator.session

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Audit trail entry, decoupled from the sessions it describes (docs/07-betrieb.md #3):
 * channelSessionId/processSessionId are historical values, not foreign keys, and
 * are expected to point into nothing once the referenced session is gone.
 */
@Entity
@Table(name = "session_event")
class SessionEvent(
    @Column(name = "channel_session_id")
    var channelSessionId: UUID? = null,

    @Column(name = "process_session_id")
    var processSessionId: UUID? = null,

    @Column(name = "event_type", nullable = false, length = 100)
    var eventType: String? = null,

    @Column(name = "source", nullable = false, length = 100)
    var source: String? = null,

    @Column(name = "payload_hash", length = 128)
    var payloadHash: String? = null
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "event_id", nullable = false)
    var eventId: UUID? = null

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant? = null

    init {
        createdAt = Instant.now()
    }
}
