package com.example.dpop.orchestrator.kc

import com.nimbusds.jose.jwk.JWKSet
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.context.annotation.Profile
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import com.example.dpop.tool_api.API_V1

/**
 * Der oeffentliche Schluessel, mit dem sich der Orchestrator bei Keycloak als Client ausweist
 * ([OrchestratorClientAssertionSigner]). Die Clients `orchestrator-admin` und
 * `orchestrator-app-token` tragen diese Adresse als `jwks.url`, Keycloak holt den Schluessel von
 * hier - genau spiegelbildlich zu `.../orchestrator-jwks/.well-known/jwks.json` der
 * keycloak-extension, wo der Orchestrator Keycloaks Peer-Auth-Schluessel holt.
 *
 * Bewusst ohne Authentisierung: hier liegt nur oeffentliches Schluesselmaterial, und wer die
 * Assertion pruefen soll, kann sich ihr gegenueber schlecht vorher ausweisen.
 */
@RestController
@RequestMapping(CLIENT_JWKS_PATH)
@Profile("keycloak")
@Tag(name = "Keycloak-Kanal", description = "Oeffentlicher Schluessel der Client-Authentisierung des Orchestrators")
class OrchestratorClientJwksController(private val signer: OrchestratorClientAssertionSigner) {

    @GetMapping(".well-known/jwks.json")
    @Operation(summary = "Public Key, gegen den Keycloak die private_key_jwt-Assertion des Orchestrators prueft")
    fun jwks(): Map<String, Any> = JWKSet(signer.key.toPublicJWK()).toJSONObject()
}

/**
 * Auch [com.example.dpop.orchestrator.ReadinessGateFilter] kennt diesen Pfad: er muss schon
 * WAEHREND der Keycloak-Migrationen antworten, denn die Migration selbst meldet sich per
 * Assertion gegen genau dieses JWKS an (KeycloakMigrationToken). Hinter dem Gate kaeme Keycloak
 * nur an ein 503 und lehnte die Anmeldung ab - die Migration wartete auf sich selbst.
 */
const val CLIENT_JWKS_PATH = "$API_V1/kc/client-jwks"
