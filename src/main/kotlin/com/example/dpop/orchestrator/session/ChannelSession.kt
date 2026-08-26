package com.example.dpop.orchestrator.session

import com.example.dpop.orchestrator.journey.AuthIntent
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "channel_session")
class ChannelSession(
    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 20)
    var channel: Channel? = null,

    @Column(name = "binding_key_ref", nullable = false, length = 64)
    var bindingKeyRef: String? = null,

    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant? = null
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "channel_session_id", nullable = false)
    var channelSessionId: UUID? = null

    @Column(name = "account_id")
    var accountId: Long? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 50)
    var state: ChannelState? = null

    @Column(name = "auth_context_id")
    var authContextId: UUID? = null

    @ManyToOne
    @JoinColumn(name = "auth_context_id", insertable = false, updatable = false)
    var authContext: AuthContext? = null

    /**
     * The channel's DURABLE lower bound; survives individual journeys. Distinct from a single
     * step-up run's target, which lives in that run's own state (docs/04-orchestrierung.md #8) -
     * the two used to share a name and were easy to confuse for one field.
     */
    @Column(name = "acr_floor", length = 50)
    var acrFloor: String? = null

    /**
     * The intent this channel was entered with. Persisted because resume and cancel must restart
     * the SAME intent: without it, an abandoned lookup login would silently fall back to whatever
     * the device happens to be linked to.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "entry_intent", nullable = false, length = 20)
    var entryIntent: AuthIntent = AuthIntent.FAST

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant? = null

    @Column(name = "last_accessed_at", nullable = false)
    var lastAccessedAt: Instant? = null

    @Version
    @Column(name = "version", nullable = false)
    var version: Long? = null

    init {
        state = ChannelState.ANONYMOUS
        createdAt = Instant.now()
        lastAccessedAt = Instant.now()
    }

    fun touch() {
        lastAccessedAt = Instant.now()
    }

    val isExpired: Boolean
        get() = expiresAt?.let { Instant.now().isAfter(it) } ?: false

    enum class Channel {
        APP, WEB
    }
}
