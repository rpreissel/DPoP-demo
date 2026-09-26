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
 * APP-channel-only token bookkeeping (docs/02-domaenenmodell.md #1; docs/12-entscheidungen.md
 * ADR-15 for why this is a table of its own) - binds the mock AccessToken/RefreshToken
 * [com.example.dpop.orchestrator.session.TokenService] issues to a channel. Deliberately holds NO
 * evidence (`amr`/`loa`/`currentAcr` etc.) - that is [AuthEvidence]'s job, referenced
 * here via [authEvidenceId] so [TokenService] can resolve the claims it mints without going
 * through the channel. [keycloakSessionId] stays unused under
 * the default profile (no real Keycloak facade there) but IS load-bearing under `keycloak`
 * (`KcTokenProvider.tokenFor` writes it, `JourneyService`'s `Transition.Logout` reads it back to
 * end exactly the one Keycloak session this token belongs to, never every session the account
 * holds) - the KEYCLOAK channel itself never creates an `AuthContext` (docs/05-api.md Abschnitt
 * 3: it has no App-style tokens to bind), but that is a different channel than the one this
 * field's session id refers to.
 */
@Entity
@Table(schema = "orchestrator", name = "auth_context")
class AuthContext(
    @Column(name = "account_id", nullable = false)
    var accountId: Long? = null,

    @Column(name = "keycloak_session_id", length = 64)
    var keycloakSessionId: String? = null
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    var authContextId: UUID? = null

    /** Pairs this token context to the evidence it was minted from - [TokenService] resolves claims through here, never by storing its own copy. */
    @Column(name = "auth_evidence_id")
    var authEvidenceId: UUID? = null

    /**
     * The AccessToken itself - the mock JWT [TokenService] mints under the default profile, the
     * real Keycloak `access_token` under `keycloak`; hence the generous length. A cache, not a
     * source of truth: it exists so repeated `.../token` calls return the same token instead of
     * minting a new one per request, and [AuthEvidenceService] clears it on every evidence change.
     */
    @Column(name = "access_token", length = 4096)
    var accessToken: String? = null

    /**
     * Never exposed to the frontend (docs/05-api.md) - a credential, unlike [accessToken]'s
     * parsed-JWT display use. Same generous length as [accessToken]: the `keycloak` profile
     * stores a real, full-size Keycloak refresh_token JWT here, not just the short opaque mock
     * secret (`mockrt_<uuid>`) the default profile still uses.
     */
    @Column(name = "refresh_token", length = 4096)
    var refreshToken: String? = null

    @Column(name = "auth_time", nullable = false)
    var authTime: Instant? = null

    @Column(name = "access_expires_at")
    var accessExpiresAt: Instant? = null

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
