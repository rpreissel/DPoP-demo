package com.example.dpop.orchestrator.admin

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
 * Backs the admin page's "Sync with Keycloak" button - an operator action, hence under
 * [ADMIN_API] and its login rather than the app contract. Only exists at all under the
 * `keycloak` Spring profile, same as
 * [com.example.dpop.orchestrator.kc.KeycloakAccountSyncService] itself; the default (no Keycloak)
 * profile has no such endpoint to call.
 */
@RestController
@RequestMapping("$ADMIN_API/keycloak/sync")
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
