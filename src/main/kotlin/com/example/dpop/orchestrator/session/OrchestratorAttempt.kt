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
import jakarta.persistence.Lob
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "orchestrator_attempt")
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "attempt_type", discriminatorType = DiscriminatorType.STRING)
abstract class OrchestratorAttempt protected constructor(
    @Column(name = "process_session_id", nullable = false)
    var processSessionId: UUID? = null,

    @Column(name = "next_context", length = 50)
    var nextContext: String? = null,

    @Column(name = "next_step", length = 50)
    var nextStep: String? = null,

    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant? = null
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "attempt_id", nullable = false)
    var attemptId: UUID? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: AttemptStatus? = null

    @Lob
    @Column(name = "missing_fields")
    var missingFields: String? = null

    @Lob
    @Column(name = "result")
    var result: String? = null

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant? = null

    @Column(name = "retry_count", nullable = false)
    var retryCount: Int = 0

    @Version
    @Column(name = "version", nullable = false)
    var version: Long? = null

    init {
        status = AttemptStatus.INPUT_REQUIRED
        createdAt = Instant.now()
        retryCount = 0
    }

    val isExpired: Boolean
        get() = expiresAt?.let { Instant.now().isAfter(it) } ?: false

    fun incrementRetryCount() {
        retryCount++
    }
}
