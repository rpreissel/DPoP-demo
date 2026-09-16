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
 * APP-channel-only token bookkeeping (docs/02-domaenenmodell.md #1) - binds the mock
 * AccessToken/RefreshToken [com.example.dpop.orchestrator.session.TokenService] issues to a
 * channel. Deliberately holds NO evidence anymore (`amr`/`loa`/`currentAcr` etc.) - that is
 * [AuthEvidence]'s job now, referenced here via [authEvidenceId] so [TokenService] can resolve
 * the claims it mints without going through the channel. [keycloakSessionId] stays unused under
 * the default profile (no real Keycloak facade there) but IS load-bearing under `keycloak`
 * (`KcTokenProvider.tokenFor` writes it, `JourneyService`'s `Transition.Logout` reads it back to
 * end exactly the one Keycloak session this token belongs to, never every session the account
 * holds) - the KEYCLOAK channel itself never creates an `AuthContext` (docs/05-api.md Abschnitt
 * 3: it has no App-style tokens to bind), but that is a different channel than the one this
 * field's session id refers to.
 */
@Entity
@Table(name = "auth_context")
class AuthContext(
    @Column(name = "account_id", nullable = false)
    var accountId: Long? = null,

    @Column(name = "keycloak_session_id", length = 255)
    var keycloakSessionId: String? = null
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "auth_context_id", nullable = false)
    var authContextId: UUID? = null

    /** Pairs this token context to the evidence it was minted from - [TokenService] resolves claims through here, never by storing its own copy. */
    @Column(name = "auth_evidence_id")
    var authEvidenceId: UUID? = null

    /** The mock AccessToken itself (a full JWT), not a short handle - hence the generous length. */
    @Column(name = "token_handle", length = 4096)
    var tokenHandle: String? = null

    /**
     * Never exposed to the frontend (docs/05-api.md) - a credential, unlike [tokenHandle]'s
     * parsed-JWT display use. Same generous length as [tokenHandle] (V25): the `keycloak` profile
     * stores a real, full-size Keycloak refresh_token JWT here, not just the short opaque mock
     * secret (`mockrt_<uuid>`) the default profile still uses.
     */
    @Column(name = "refresh_token_handle", length = 4096)
    var refreshTokenHandle: String? = null

    @Column(name = "auth_time", nullable = false)
    var authTime: Instant? = null

    @Column(name = "token_expires_at")
    var tokenExpiresAt: Instant? = null

    @Column(name = "refresh_expires_at")
    var refreshExpiresAt: Instant? = null

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant? = null

    @Version
    @Column(name = "version", nullable = false)
    var version: Long? = null

    init {
        val now = Instant.now()
        authTime = now
        updatedAt = now
    }
}
