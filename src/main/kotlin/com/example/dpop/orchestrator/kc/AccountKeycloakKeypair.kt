package com.example.dpop.orchestrator.kc

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.Instant

/**
 * One asymmetric keypair per account, used only by the `keycloak` profile's real
 * token grant: Keycloak reads the public half through the account lookup ([KcAccountViews]), the
 * private half signs the assertion
 * [com.example.dpop.orchestrator.session.KcTokenProvider] presents to Keycloak's custom
 * `urn:dpop-demo:account-token` grant. Demo-only: [privateKeyJwk] is plaintext, not
 * encrypted at rest - it is never returned by any API, only ever read back by
 * [KcTokenProvider] to sign an outgoing assertion.
 */
@Entity
@Table(schema = "orchestrator", name = "keycloak_keypair")
class AccountKeycloakKeypair(
    @Id
    @Column(name = "account_id")
    var accountId: Long = 0,

    @Column(name = "public_key_jwk", length = 2000, nullable = false)
    var publicKeyJwk: String = "",

    @Column(name = "private_key_jwk", length = 2000, nullable = false)
    var privateKeyJwk: String = "",

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now()
)

@Repository
interface AccountKeycloakKeypairRepository : JpaRepository<AccountKeycloakKeypair, Long>
