package com.example.dpop.orchestrator.session

import com.example.dpop.orchestrator.journey.AuthIntent
import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
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

    /** APP-only (docs/ideen/web-keycloak-kanal.md #5) - null on KEYCLOAK channels, which anchor via [channelAnchor] instead. */
    @Column(name = "binding_key_ref", length = 64)
    var bindingKeyRef: String? = null,

    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant? = null
) {
    /**
     * KEYCLOAK-only kc-anchor (docs/ideen/web-keycloak-kanal.md #2/#4) - always the extension's own
     * `channelSessionId` for this flow run, carried in the peer-auth assertion so
     * [ChannelAccessGuard] can verify the caller acts for this exact flow run: without this, a
     * leaked `channelSessionId` plus any validly-signed Keycloak assertion would be enough to
     * hijack the channel, since the signature alone only proves "this really came from Keycloak,"
     * not "for this specific one of Keycloak's many concurrent flows." Deliberately NOT Keycloak's
     * actual, durable `UserSessionModel` id: two CONCURRENT flow runs sharing the same underlying
     * SSO session (e.g. two browser tabs both stepping up at once) would then share the same
     * anchor value too, giving up the per-flow-run isolation this column exists for. Stored under
     * the column's old name (`kc_session_id`) - only the Kotlin property was renamed for clarity.
     *
     * Evidence continuity across SEPARATE flow runs is unrelated to this column - a Keycloak-side
     * concern instead (docs/ideen/web-keycloak-kanal.md #6/#9): the `OrchestratorAuthenticator`'s
     * end-of-flow lifecycle hook fetches a signed `RestoreData` token bound explicitly to
     * Keycloak's own, durable `UserSessionModel` id (`GET .../restore-data?kcSessionId=...` - a
     * completely different value from this column) and stashes it in a `UserSessionModel` note; a
     * later step-up's initial authenticator reads it back out and hands it to the fresh channel's
     * own first `PATCH` call as `restoreData`.
     */
    @Column(name = "kc_session_id", length = 64)
    var channelAnchor: String? = null

    /**
     * Self-assigned rather than `@GeneratedValue`, so the kc-facade can override it with its own,
     * client-chosen id before the first save (docs/ideen/web-keycloak-kanal.md #6 - upsert
     * semantics, idempotent retries) while APP callers, which never touch this field, keep
     * getting a fresh random id exactly as before.
     */
    @Id
    @Column(name = "channel_session_id", nullable = false)
    var channelSessionId: UUID? = null

    @Column(name = "account_id")
    var accountId: Long? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 50)
    var state: ChannelState? = null

    /** APP-only (docs/ideen/web-keycloak-kanal.md #6) - the KEYCLOAK channel never sets this, it has no App-style tokens to bind ([AuthContext]'s own doc). */
    @Column(name = "auth_context_id")
    var authContextId: UUID? = null

    @ManyToOne
    @JoinColumn(name = "auth_context_id", insertable = false, updatable = false)
    var authContext: AuthContext? = null

    /** Both channel types (docs/ideen/web-keycloak-kanal.md #6) - the evidence itself, unlike the App-only [authContextId]. */
    @Column(name = "auth_evidence_id")
    var authEvidenceId: UUID? = null

    @ManyToOne
    @JoinColumn(name = "auth_evidence_id", insertable = false, updatable = false)
    var authEvidence: AuthEvidence? = null

    /**
     * Whether at least one authentication factor has actually been proven on THIS channel (an
     * [AuthEvidence] exists) - distinct from `state == AUTHENTICATED`, which additionally requires
     * the full required ACR to be reached (a channel mid-chain toward loa2 already has this after
     * its first factor, well before `state` reflects it). [accountId] alone is NOT this: a
     * recognized device already carries an `accountId` from `DeviceAccountLink` before any proof
     * was made here - account-level details (active methods, ACR/AMR) are only safe to reveal
     * once something was actually proven, never merely because the device was recognized.
     */
    val hasProvenFactor: Boolean
        get() = authEvidenceId != null

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
    var entryIntent: AuthIntent = AuthIntent.FAST_ACCESS

    /**
     * The toolIds this client declared support for at channel creation - fixed for the channel's
     * whole lifetime, never updated afterwards (docs/03-tool-architektur.md, availability). One
     * axis of tool availability; the other is the backend-wide ToolAvailabilityService kill-switch.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "channel_session_available_tools", joinColumns = [JoinColumn(name = "channel_session_id")])
    @Column(name = "tool_id")
    var availableClientTools: MutableSet<String> = mutableSetOf()

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant? = null

    @Column(name = "last_accessed_at", nullable = false)
    var lastAccessedAt: Instant? = null

    @Version
    @Column(name = "version", nullable = false)
    var version: Long? = null

    init {
        channelSessionId = UUID.randomUUID()
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
        APP, KEYCLOAK
    }
}
