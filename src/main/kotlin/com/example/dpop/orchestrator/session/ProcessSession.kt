package com.example.dpop.orchestrator.session

import jakarta.persistence.Column
import jakarta.persistence.DiscriminatorColumn
import jakarta.persistence.DiscriminatorType
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Inheritance
import jakarta.persistence.InheritanceType
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
import java.util.UUID

/**
 * Abstract base with shared lifecycle, account reference and routing state
 * (docs/02-domaenenmodell.md #2). Process-specific fields live only on the
 * concrete subclasses, never here.
 */
@Entity
@Table(name = "process_session")
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "purpose", discriminatorType = DiscriminatorType.STRING)
abstract class ProcessSession protected constructor(
    @Column(name = "channel_session_id", nullable = false)
    var channelSessionId: UUID? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, length = 20, insertable = false, updatable = false)
    var purpose: ProcessPurpose? = null,

    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant? = null
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "process_session_id", nullable = false)
    var processSessionId: UUID? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 20)
    var state: ProcessState? = null

    @Column(name = "account_id")
    var accountId: Long? = null

    // Routing state (docs/04-orchestrierung.md): where the client goes next.
    @Column(name = "next_type", length = 10)
    var nextType: String? = null

    @Column(name = "next_tool_id", length = 50)
    var nextToolId: String? = null

    /**
     * Which ToolSession is actually authorized to act as [nextToolId] right now - not just any
     * ToolSession row with a matching toolId. Without this, an orphaned/superseded ToolSession
     * (e.g. from a duplicate activation request) would keep passing a toolId-only check even
     * though its own tool-module data was never populated, surfacing as a confusing
     * "Unknown tool session" error deep inside the module instead of a clean 409 here.
     */
    @Column(name = "next_tool_session_id")
    var nextToolSessionId: UUID? = null

    @Column(name = "next_context", length = 50)
    var nextContext: String? = null

    @Column(name = "next_step", length = 50)
    var nextStep: String? = null

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant? = null

    @Column(name = "consumed_at")
    var consumedAt: Instant? = null

    @Version
    @Column(name = "version", nullable = false)
    var version: Long? = null

    init {
        state = ProcessState.STARTED
        createdAt = Instant.now()
    }

    /**
     * [toolSessionId] is null for a fresh offer (the tool hasn't been activated yet - a client
     * must still call activate to mint one) and non-null when an already-active ToolSession is
     * merely progressing to its next internal step (docs/06-ablaeufe.md).
     */
    fun setNextTool(toolId: String, step: String, toolSessionId: UUID? = null) {
        nextType = "tool"
        nextToolId = toolId
        nextToolSessionId = toolSessionId
        nextContext = null
        nextStep = step
    }

    fun setNextFlow(context: String, step: String) {
        nextType = "flow"
        nextToolId = null
        nextToolSessionId = null
        nextContext = context
        nextStep = step
    }

    fun consume() {
        consumedAt = Instant.now()
        state = ProcessState.CONSUMED
    }

    fun fail() {
        state = ProcessState.FAILED
    }

    /** User-initiated abandonment, distinct from Failed (retry-exhausted) - docs/02-domaenenmodell.md #4. */
    fun cancel() {
        state = ProcessState.CANCELLED
    }

    val isExpired: Boolean
        get() = expiresAt?.let { Instant.now().isAfter(it) } ?: false

    val isConsumed: Boolean
        get() = consumedAt != null
}
