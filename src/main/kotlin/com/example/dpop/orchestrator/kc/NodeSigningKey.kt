package com.example.dpop.orchestrator.kc

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import org.springframework.stereotype.Repository

/**
 * Ein Schluesselpaar, das dem Knoten gehoert statt einem Account. Pro [purpose] genau eine Zeile - der Zweck IST der
 * Primaerschluessel, siehe die Begruendung in V4__node_signing_key.sql.
 *
 * Demo-Rahmen: [privateKeyJwk] liegt im Klartext (ADR-22), wird von keiner API herausgegeben und
 * nur zum Signieren ausgehender Assertions gelesen.
 */
@Entity
@Table(schema = "orchestrator", name = "node_signing_key")
class NodeSigningKey(
    @Id
    @Column(name = "purpose", length = 64)
    var purpose: String = "",

    @Column(name = "public_key_jwk", length = 2000, nullable = false)
    var publicKeyJwk: String = "",

    @Column(name = "private_key_jwk", length = 2000, nullable = false)
    var privateKeyJwk: String = "",

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
) {
    companion object {
        /**
         * Client-Authentisierung gegenueber Keycloak ([OrchestratorClientAssertionSigner]) - ein
         * Zweck je Client, damit kein Client mit dem Schluessel eines anderen signiert.
         */
        fun keycloakClientAuth(clientId: String): String = "keycloak-client-auth:$clientId"
    }
}

@Repository
interface NodeSigningKeyRepository : JpaRepository<NodeSigningKey, String> {
    /**
     * An INSERT and nothing else: when two instances start at once, the second must fail on the
     * primary key and take the first one's pair. `save` would merge - read, find the rival's row,
     * and overwrite its key with a different one (review 2026-09-26).
     */
    @Transactional
    @Modifying
    @Query(
        value = "INSERT INTO orchestrator.node_signing_key (purpose, public_key_jwk, private_key_jwk, created_at) " +
            "VALUES (:purpose, :publicKeyJwk, :privateKeyJwk, :createdAt)",
        nativeQuery = true,
    )
    fun insert(
        @Param("purpose") purpose: String,
        @Param("publicKeyJwk") publicKeyJwk: String,
        @Param("privateKeyJwk") privateKeyJwk: String,
        @Param("createdAt") createdAt: Instant,
    )
}
