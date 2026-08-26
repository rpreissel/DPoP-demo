package com.example.dpop.orchestrator.session

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
@Entity
@Table(name = "tool_session")
class ToolSession(
    @Column(name = "journey_id", nullable = false)
    var journeyId: UUID? = null,

    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant? = null
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "tool_session_id", nullable = false)
    var toolSessionId: UUID? = null

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant? = null

    @Version
    @Column(name = "version", nullable = false)
    var version: Long? = null

    init {
        createdAt = Instant.now()
    }

    val isExpired: Boolean
        get() = expiresAt?.let { Instant.now().isAfter(it) } ?: false
}
