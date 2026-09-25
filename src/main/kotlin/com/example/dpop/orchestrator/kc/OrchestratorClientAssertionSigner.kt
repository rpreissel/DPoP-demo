package com.example.dpop.orchestrator.kc

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.time.Instant
import java.util.Date
import java.util.UUID
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/**
 * Weist den Orchestrator bei Keycloak als Client aus - mit einer signierten Assertion
 * (`private_key_jwt`, RFC 7523) statt eines `client_secret`.
 *
 * Damit tragen beide Richtungen dasselbe Prinzip (docs/12-entscheidungen.md ADR-7/ADR-9,
 * "Signatur statt Secret"): Keycloak weist sich beim Orchestrator per PeerAuthAssertion aus, der
 * Orchestrator sich bei Keycloak hiermit. Kein geteiltes Geheimnis mehr in Konfiguration,
 * Compose-Datei oder Realm - der oeffentliche Schluessel steht im JWKS
 * ([OrchestratorClientJwksController]), das jeder Client in Keycloak als `jwks.url` traegt.
 *
 * **Ein Schluessel je Client** (Review 2026-09, S-3): `orchestrator-admin`, `orchestrator-app-token`
 * und `orchestrator-migration` haben sehr verschiedene Rechte. Mit einem gemeinsamen Schluessel
 * waere die Rechtetrennung zwischen ihnen nur Kosmetik - wer die Assertion des rechtlosen
 * Token-Clients faelschen kann, koennte sich genauso als Migrationsclient anmelden. Jeder Client
 * hat deshalb sein eigenes Paar und sein eigenes JWKS; Keycloak akzeptiert fuer einen Client nur
 * dessen Schluessel. Andere Client-Ids signiert dieser Knoten nicht.
 *
 * Die Paare liegen in der Datenbank (`orchestrator.node_signing_key`, eine Zeile je Client), siehe
 * [NodeKeys].
 */
@Component
@Profile("keycloak")
class OrchestratorClientAssertionSigner(
    private val keys: NodeSigningKeyRepository,
    @Value("\${keycloak-sync.admin-client-id}") adminClientId: String,
    @Value("\${keycloak-sync.app-client-id}") appClientId: String,
) {
    /** Die Clients, fuer die dieser Knoten signiert - jeder mit eigenem Schluessel. */
    val clientIds: Set<String> = setOf(adminClientId, appClientId, KeycloakMigrationToken.CLIENT_ID)

    private val nodeKeys = NodeKeys(keys)

    /** Oeffentlicher Schluessel von [clientId], oder `null` fuer einen Client, den dieser Knoten nicht vertritt. */
    fun publicKeyOf(clientId: String): ECKey? =
        if (clientId in clientIds) keyOf(clientId).toPublicJWK() else null

    private fun keyOf(clientId: String): ECKey {
        require(clientId in clientIds) { "Fuer Client '$clientId' signiert dieser Orchestrator nicht" }
        return nodeKeys.keyFor(NodeSigningKey.keycloakClientAuth(clientId), clientId)
    }

    /**
     * [audience] ist die Realm-Adresse, unter der Keycloak sich selbst kennt - also die
     * OEFFENTLICHE (KC_HOSTNAME), nicht der interne Weg, ueber den der Aufruf tatsaechlich kommt:
     * Keycloak bildet die erwartete Audience aus seiner eigenen Frontend-URL.
     *
     * Bewusst genau eine: Keycloak lehnt eine Assertion mit mehreren Audiences rundheraus ab
     * ("Multiple audiences not allowed"), ein vorsorgliches Nennen beider Adressen scheitert also.
     */
    fun assertionFor(clientId: String, audience: String): String {
        val key = keyOf(clientId)
        val now = Instant.now()
        val claims = JWTClaimsSet.Builder()
            .issuer(clientId)
            .subject(clientId)
            .audience(audience)
            .jwtID(UUID.randomUUID().toString())
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plusSeconds(ASSERTION_TTL_SECONDS)))
            .build()
        val jwt = SignedJWT(
            JWSHeader.Builder(JWSAlgorithm.ES256).keyID(key.keyID).type(JOSEObjectType.JWT).build(),
            claims,
        )
        jwt.sign(ECDSASigner(key))
        return jwt.serialize()
    }

    private companion object {
        /** Kurzlebig wie jede andere Assertion in diesem Projekt - sie wird sofort eingeloest. */
        const val ASSERTION_TTL_SECONDS = 60L
    }
}
