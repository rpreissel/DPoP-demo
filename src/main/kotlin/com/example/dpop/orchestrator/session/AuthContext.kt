package com.example.dpop.orchestrator.session

import com.example.dpop.tool_spi.FactorType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

/**
 * Server-side IAM context (docs/02-domaenenmodell.md #1). keycloakSessionId/keycloakSubject
 * exist for the shape of the target model but stay unused without the real Keycloak facade
 * (docs/11-umsetzungsplan.md, explicitly out of scope). tokenHandle/refreshTokenHandle and
 * their expiry fields ARE used, by [com.example.dpop.orchestrator.session.TokenService]'s
 * mock AccessToken/RefreshToken issuance.
 */
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

    /** The mock AccessToken itself (a full JWT), not a short handle - hence the generous length. */
    @Column(name = "token_handle", length = 4096)
    var tokenHandle: String? = null

    /** Never exposed to the frontend (docs/05-api.md) - a credential, unlike [tokenHandle]'s parsed-JWT display use. */
    @Column(name = "refresh_token_handle", length = 255)
    var refreshTokenHandle: String? = null

    @Column(name = "current_acr", length = 50)
    var currentAcr: String? = null

    /** Methods proven in THIS session; does not survive it (docs/04-orchestrierung.md #1). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "current_amr", nullable = false)
    var currentAmr: MutableList<String> = mutableListOf()

    /**
     * Kept alongside currentAmr, not derived from it: amr values name procedures,
     * not factor kinds (docs/02-domaenenmodell.md #5).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "current_factor_types", nullable = false)
    var currentFactorTypes: MutableSet<FactorType> = mutableSetOf()

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

    /** One tool run can report several amr values at once. */
    fun addAmr(values: List<String>) {
        for (v in values) if (v !in currentAmr) currentAmr.add(v)
        updatedAt = Instant.now()
    }

    fun addFactorTypes(values: Set<FactorType>) {
        currentFactorTypes.addAll(values)
        updatedAt = Instant.now()
    }
}
