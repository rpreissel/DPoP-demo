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
 * per response from the module's own data.
 */
@Entity
@Table(name = "tool_session")
class ToolSession(
    @Column(name = "process_session_id", nullable = false)
    var processSessionId: UUID? = null,

    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant? = null
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "tool_session_id", nullable = false)
    var toolSessionId: UUID? = null

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant? = null

    @Column(name = "retry_count", nullable = false)
    var retryCount: Int = 0

    @Version
    @Column(name = "version", nullable = false)
    var version: Long? = null

    init {
        createdAt = Instant.now()
    }

    fun registerFailedAttempt() {
        retryCount += 1
    }

    val isExpired: Boolean
        get() = expiresAt?.let { Instant.now().isAfter(it) } ?: false
}
