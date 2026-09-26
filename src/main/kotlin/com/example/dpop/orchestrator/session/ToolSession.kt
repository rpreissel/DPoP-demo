package com.example.dpop.orchestrator.session

import jakarta.persistence.Enumerated
import jakarta.persistence.EnumType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
import java.util.UUID

/**
 * Third and shortest-lived session level (docs/03-tool-architektur.md #1). A single
 * concrete class, no subtypes: it carries only technical lifecycle metadata. Neither
 * toolId nor stepData live here - toolId comes from the route, stepData is rebuilt
 * per response from the module's own data. No retry counter either: the attempt budget spans the
 * whole AuthJourney (docs/04-orchestrierung.md #7), because a tool-local counter cannot stop
 * brute force that simply moves on to the next state.
 */
/** How a tool session ends - a state of its own rather than an expiry moved to "now". */
enum class ToolSessionStatus {
    RUNNING,
    /** Completed - never completable again (review 2026-09, S-1). */
    DONE,
    /** Left via Back/Switch. */
    ABANDONED
}

@Entity
@Table(schema = "orchestrator", name = "tool_session")
class ToolSession(
    @Column(name = "journey_id", nullable = false)
    var journeyId: UUID? = null,

    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant? = null
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    var toolSessionId: UUID? = null

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    var status: ToolSessionStatus = ToolSessionStatus.RUNNING

    @Version
    @Column(name = "version", nullable = false)
    var version: Long? = null

    init {
        createdAt = Instant.now()
    }

    val isExpired: Boolean
        get() = expiresAt?.let { Instant.now().isAfter(it) } ?: false

    /** Still accepts input: running and within its time. */
    val isUsable: Boolean
        get() = status == ToolSessionStatus.RUNNING && !isExpired
}

/** Set on the first save, never null afterwards - see `ChannelSession.id`. */
val ToolSession.id: UUID get() = checkNotNull(toolSessionId) { "ToolSession not saved yet" }
