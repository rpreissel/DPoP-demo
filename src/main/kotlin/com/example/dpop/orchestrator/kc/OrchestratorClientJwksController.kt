package com.example.dpop.orchestrator.kc

import com.nimbusds.jose.jwk.JWKSet
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.context.annotation.Profile
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import com.example.dpop.tool_api.API_V1

/**
 * Die oeffentlichen Schluessel, mit denen sich der Orchestrator bei Keycloak als Client ausweist
 * ([OrchestratorClientAssertionSigner]) - einer je Client, unter
 * `.../client-jwks/{clientId}/.well-known/jwks.json`. Jeder Client in Keycloak traegt genau seine
 * Adresse als `jwks.url` (die Realm-Clients per Migration, `orchestrator-migration` per
 * SPI-Konfiguration des Keycloak-Containers). Spiegelbildlich zu
 * `.../orchestrator-jwks/.well-known/jwks.json` der keycloak-extension, wo der Orchestrator
 * Keycloaks Peer-Auth-Schluessel holt.
 *
 * Bewusst ohne Authentisierung: hier liegt nur oeffentliches Schluesselmaterial, und wer die
 * Assertion pruefen soll, kann sich ihr gegenueber schlecht vorher ausweisen. Ein Client, den
 * dieser Knoten nicht vertritt, bekommt 404.
 */
@RestController
@RequestMapping(CLIENT_JWKS_PATH)
@Profile("keycloak")
@Tag(name = "Keycloak-Kanal", description = "Oeffentliche Schluessel der Client-Authentisierung des Orchestrators")
class OrchestratorClientJwksController(private val signer: OrchestratorClientAssertionSigner) {

    @GetMapping("{clientId}/.well-known/jwks.json")
    @Operation(summary = "Public Key, gegen den Keycloak die private_key_jwt-Assertion dieses Clients prueft")
    fun jwks(@PathVariable clientId: String): ResponseEntity<Map<String, Any>> =
        signer.publicKeyOf(clientId)
            ?.let { ResponseEntity.ok(JWKSet(it).toJSONObject()) }
            ?: ResponseEntity.notFound().build()
}

/**
 * Auch [com.example.dpop.orchestrator.ReadinessGateFilter] kennt diesen Pfad: er muss schon
 * WAEHREND der Keycloak-Migrationen antworten, denn die Migration selbst meldet sich per
 * Assertion gegen genau dieses JWKS an (KeycloakMigrationToken). Hinter dem Gate kaeme Keycloak
 * nur an ein 503 und lehnte die Anmeldung ab - die Migration wartete auf sich selbst.
 */
const val CLIENT_JWKS_PATH = "$API_V1/kc/client-jwks"
