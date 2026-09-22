package com.example.dpop.orchestrator.kc

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.time.Instant
import java.util.Date
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Component

/**
 * Weist den Orchestrator bei Keycloak als Client aus - mit einer signierten Assertion
 * (`private_key_jwt`, RFC 7523) statt eines `client_secret`.
 *
 * Damit tragen beide Richtungen dasselbe Prinzip (docs/12-entscheidungen.md ADR-7/ADR-9,
 * "Signatur statt Secret"): Keycloak weist sich beim Orchestrator per PeerAuthAssertion aus, der
 * Orchestrator sich bei Keycloak hiermit. Kein geteiltes Geheimnis mehr in Konfiguration,
 * Compose-Datei oder Realm - der oeffentliche Schluessel steht im JWKS
 * ([OrchestratorClientJwksController]), das die Clients in der Migration als `jwks.url` tragen.
 *
 * Das Paar liegt in der Datenbank (`orchestrator.node_signing_key`), nicht im Prozessspeicher:
 * mehrere Orchestrator-Instanzen tragen so dieselbe Client-Identitaet, und ein Neustart aendert
 * sie nicht. Erzeugt wird es beim ersten Zugriff; starten zwei Instanzen gleichzeitig, laeuft die
 * zweite in den Primaerschluessel und liest das bereits angelegte Paar.
 */
@Component
@Profile("keycloak")
class OrchestratorClientAssertionSigner(private val keys: NodeSigningKeyRepository) {

    val key: ECKey = loadOrCreate()

    private val signer = ECDSASigner(key)

    private fun loadOrCreate(): ECKey {
        stored()?.let { return it }
        val generated = ECKeyGenerator(Curve.P_256)
            .keyID("orchestrator-client-" + Instant.now().toEpochMilli())
            .algorithm(JWSAlgorithm.ES256)
            .keyUse(KeyUse.SIGNATURE)
            .generate()
        return try {
            keys.save(
                NodeSigningKey(
                    purpose = NodeSigningKey.KEYCLOAK_CLIENT_AUTH,
                    publicKeyJwk = generated.toPublicJWK().toJSONString(),
                    privateKeyJwk = generated.toJSONString(),
                ),
            )
            generated
        } catch (e: DataIntegrityViolationException) {
            // Eine zweite Instanz war schneller - ihr Paar gilt, nicht das gerade erzeugte.
            log.info("Client-Schluessel wurde parallel angelegt, uebernehme den vorhandenen", e)
            stored() ?: throw e
        }
    }

    private fun stored(): ECKey? =
        keys.findById(NodeSigningKey.KEYCLOAK_CLIENT_AUTH).orElse(null)?.let { ECKey.parse(it.privateKeyJwk) }

    /**
     * [audience] ist die Realm-Adresse, unter der Keycloak sich selbst kennt - also die
     * OEFFENTLICHE (KC_HOSTNAME), nicht der interne Weg, ueber den der Aufruf tatsaechlich kommt:
     * Keycloak bildet die erwartete Audience aus seiner eigenen Frontend-URL.
     *
     * Bewusst genau eine: Keycloak lehnt eine Assertion mit mehreren Audiences rundheraus ab
     * ("Multiple audiences not allowed"), ein vorsorgliches Nennen beider Adressen scheitert also.
     */
    fun assertionFor(clientId: String, audience: String): String {
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
        jwt.sign(signer)
        return jwt.serialize()
    }

    private companion object {
        val log = LoggerFactory.getLogger(OrchestratorClientAssertionSigner::class.java)

        /** Kurzlebig wie jede andere Assertion in diesem Projekt - sie wird sofort eingeloest. */
        const val ASSERTION_TTL_SECONDS = 60L
    }
}
