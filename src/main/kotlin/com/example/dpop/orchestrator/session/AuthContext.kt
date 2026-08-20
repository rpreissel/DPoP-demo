package com.example.dpop.orchestrator.session

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Lob
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "auth_context")
class AuthContext(
    @Column(name = "account_id", nullable = false)
    var accountId: Long? = null,

    @Column(name = "keycloak_session_id", length = 255)
    var keycloakSessionId: String? = null,

    @Column(name = "keycloak_subject", length = 255)
    var keycloakSubject: String? = null
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "auth_context_id", nullable = false)
    var authContextId: UUID? = null

    @Column(name = "token_handle", length = 255)
    var tokenHandle: String? = null

    @Column(name = "current_acr", length = 50)
    var currentAcr: String? = null

    @Lob
    @Column(name = "current_amr")
    var currentAmr: String? = null

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
