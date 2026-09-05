package com.example.dpop.orchestrator.api.v1.kc

import com.example.dpop.orchestrator.kc.KeycloakAccountSyncService
import com.example.dpop.orchestrator.kc.KeycloakSyncResult
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.context.annotation.Profile
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Backs the demo frontend's "Sync with Keycloak" settings button - only exists at all under the
 * `keycloak` Spring profile (docs/ideen/web-keycloak-kanal.md), same as
 * [com.example.dpop.orchestrator.kc.KeycloakAccountSyncService] itself; the default (Mock-Keycloak)
 * profile has no such endpoint to call.
 */
@RestController
@RequestMapping("/orchestrator/api/v1/kc/sync")
@Tag(name = "KC account sync", description = "Explicit full reconciliation between orchestrator accounts and Keycloak users - demo/debug only")
@Profile("keycloak")
class KeycloakSyncController(private val keycloakAccountSyncService: KeycloakAccountSyncService) {

    @PostMapping
    @Operation(
        summary = "Sync every account into Keycloak, then delete orphaned Keycloak users",
        description = "Upserts a Keycloak user for every current orchestrator account, then deletes any " +
            "Keycloak user carrying an orchestratorAccountId attribute that no longer matches a real account."
    )
    fun sync(): ResponseEntity<KeycloakSyncResult> = ResponseEntity.ok(keycloakAccountSyncService.syncAll())
}
