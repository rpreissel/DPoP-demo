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
    @Column(name = "status", nullable = false, length = 20)
    var status: ProcessStatus? = null

    @Column(name = "account_id")
    var accountId: Long? = null

    @Column(name = "selected_identification_method", length = 50)
    var selectedIdentificationMethod: String? = null

    @Column(name = "selected_authentication_method", length = 50)
    var selectedAuthenticationMethod: String? = null

    @Column(name = "person_id")
    var personId: Long? = null

    @Lob
    @Column(name = "pending_challenge")
    var pendingChallenge: String? = null

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant? = null

    @Column(name = "consumed_at")
    var consumedAt: Instant? = null

    @Version
    @Column(name = "version", nullable = false)
    var version: Long? = null

    init {
        status = ProcessStatus.ACTIVE
        createdAt = Instant.now()
    }

    fun consume() {
        consumedAt = Instant.now()
        status = ProcessStatus.COMPLETED
    }

    val isExpired: Boolean
        get() = expiresAt?.let { Instant.now().isAfter(it) } ?: false

    val isConsumed: Boolean
        get() = consumedAt != null
}
