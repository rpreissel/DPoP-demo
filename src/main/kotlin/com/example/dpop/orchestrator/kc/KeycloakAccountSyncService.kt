package com.example.dpop.orchestrator.kc

import com.example.dpop.account.AccountService
import com.example.dpop.ext_stammdaten.ExtStammdatenService
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service

data class KeycloakSyncResult(val upserted: Int, val deletedOrphans: Int)

/**
 * The explicit, full-reconciliation counterpart of [KeycloakAccountSyncListener]'s per-event sync
 * - "Sync with Keycloak" in the demo frontend's settings, for the cases the event-driven path
 * can't cover on its own: a missed event (app down when it fired), or a Keycloak user that was
 * ever hand-created/left over some other way and no longer corresponds to any real account.
 * Push-only in one direction (orchestrator accounts are authoritative) - never creates an
 * orchestrator account FROM a Keycloak user found here.
 */
@Service
@Profile("keycloak")
class KeycloakAccountSyncService(
    private val accountService: AccountService,
    private val extStammdatenService: ExtStammdatenService,
    private val keycloakAdminClient: KeycloakAdminClient,
    private val accountKeypairService: AccountKeypairService
) {
    private val log = LoggerFactory.getLogger(KeycloakAccountSyncService::class.java)

    fun syncAll(): KeycloakSyncResult {
        val existingAccountIds = accountService.allAccountIds().toSet()

        existingAccountIds.forEach { accountId ->
            val profile = accountService.findAccount(accountId) ?: return@forEach
            val person = extStammdatenService.findPersonById(profile.personId)
            keycloakAdminClient.upsertUser(accountId, profile.email, person?.vorname, person?.name)
            val keypair = accountKeypairService.keypairFor(accountId)
            val activeMethods = profile.activeAuthenticationMethods.map { it.method }.distinct()
            keycloakAdminClient.setPublicKeyCredential(accountId, keypair.publicKeyJwk, activeMethods)
        }

        val syncedAccountIds = keycloakAdminClient.findAllSyncedAccountIds()
        val orphans = syncedAccountIds - existingAccountIds
        orphans.forEach { keycloakAdminClient.deleteUser(it) }

        log.info(
            "Keycloak full sync: upserted {} account(s), deleted {} orphaned Keycloak user(s)",
            existingAccountIds.size, orphans.size
        )
        return KeycloakSyncResult(upserted = existingAccountIds.size, deletedOrphans = orphans.size)
    }
}
