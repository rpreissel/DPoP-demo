package com.example.dpop.orchestrator.kc

import com.example.dpop.account.AccountService
import com.example.dpop.ext_personenverzeichnis.Personenverzeichnis
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
    private val personenverzeichnis: Personenverzeichnis,
    private val keycloakAdminClient: KeycloakAdminClient,
    private val accountKeypairService: AccountKeypairService
) {
    private val log = LoggerFactory.getLogger(KeycloakAccountSyncService::class.java)

    fun syncAll(): KeycloakSyncResult {
        val existingAccountIds = accountService.allAccountIds().toSet()
        var upserted = 0

        existingAccountIds.forEach { accountId ->
            val profile = accountService.findAccount(accountId) ?: return@forEach
            // Nothing worth mirroring yet (REGISTER "Enrollment zuerst" account, freshly created,
            // docs/04-orchestrierung.md) - a Keycloak user needs an email/username; a later full
            // sync (or the event-driven path once one is confirmed) creates it for real.
            if (profile.email == null) return@forEach
            // Unidentified account (REGISTER "Enrollment zuerst") - no person to look up yet.
            val person = profile.personId?.let { personenverzeichnis.findPersonById(it) }
            val mirror = kcUserMirror(
                profile, person,
                accountService.establishedClaimValues(profile.accountId, MIRRORED_CLAIM_TYPES)
            )
            keycloakAdminClient.upsertUser(
                accountId, profile.email, profile.emailConfirmed,
                mirror.firstName, mirror.lastName, mirror.attributes
            )
            val keypair = accountKeypairService.keypairFor(accountId)
            val activeMethods = profile.activeAuthenticationMethods.map { it.method }.distinct()
            keycloakAdminClient.setPublicKeyCredential(accountId, keypair.publicKeyJwk, activeMethods)
            upserted++
        }

        // Skipped (still emailless) accounts are deliberately absent from Keycloak - never treated
        // as orphans just because they haven't been upserted yet.
        val syncedAccountIds = keycloakAdminClient.findAllSyncedAccountIds()
        val orphans = syncedAccountIds - existingAccountIds
        orphans.forEach { keycloakAdminClient.deleteUser(it) }

        log.info(
            "Keycloak full sync: upserted {} account(s), deleted {} orphaned Keycloak user(s)",
            upserted, orphans.size
        )
        return KeycloakSyncResult(upserted = upserted, deletedOrphans = orphans.size)
    }
}
