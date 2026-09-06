package com.example.dpop.orchestrator.api.v1.kc

import com.example.dpop.orchestrator.journeylog.JourneyLogResponse
import com.example.dpop.orchestrator.journeylog.JourneyLogService
import com.example.dpop.orchestrator.kc.KeycloakOidcTokenValidator
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.context.annotation.Profile
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Demo/debug-only read path for the Web-Kanal test UI's Journey-Log tab (`kc.oidc`'s own
 * doc in `application-keycloak.yml`): the browser presents its own, real Keycloak AccessToken
 * directly - the production kc-facade contract ("Browser spricht nie direkt mit dem
 * Orchestrator") only binds actual login/tool-execution traffic, never this one, purely
 * informational read. Not facade-neutral, not part of [KcChannelController] - a single, narrow
 * endpoint with its own bearer-proof kind ([KeycloakOidcTokenValidator]).
 */
@RestController
@RequestMapping("/orchestrator/api/v1/kc/me")
@Tag(name = "KC me", description = "Demo-only: read-your-own-journey-log via a real Keycloak AccessToken")
@Profile("keycloak")
class KcMeController(
    private val oidcTokenValidator: KeycloakOidcTokenValidator,
    private val journeyLogService: JourneyLogService
) {

    @GetMapping("/journey-log")
    @Operation(
        summary = "Every journey-log entry for the account this AccessToken belongs to",
        description = "Demo/debug only - the caller's own real Keycloak AccessToken as Bearer auth, " +
            "verified against Keycloak's realm signing key. Not part of the production kc-facade contract."
    )
    fun journeyLog(@RequestHeader("Authorization") authorization: String?): ResponseEntity<JourneyLogResponse> {
        val token = authorization?.removePrefix("Bearer ")?.trim()
        val accountId = oidcTokenValidator.accountIdOf(token)
        return ResponseEntity.ok(journeyLogService.getLogForAccount(accountId))
    }
}
