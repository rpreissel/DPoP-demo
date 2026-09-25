package com.example.dpop.orchestrator.kc

import com.example.dpop.account.AccountService
import com.example.dpop.tool_api.PersonMasterData
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service

/**
 * [conflicts]: accounts whose Keycloak user could not be resolved without taking over another
 * account's mirror (review 2026-09, S-2) - left untouched and named in the log.
 */
data class KeycloakSyncResult(val upserted: Int, val deletedOrphans: Int, val conflicts: Int = 0)

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
    private val personMasterData: PersonMasterData,
    private val keycloakAdminClient: KeycloakAdminClient,
    private val accountKeypairService: AccountKeypairService
) {
    private val log = LoggerFactory.getLogger(KeycloakAccountSyncService::class.java)

    fun syncAll(): KeycloakSyncResult {
        val existingAccountIds = accountService.allAccountIds().toSet()
        var upserted = 0

        // A conflict (a Keycloak user wearing this account's address but belonging to another,
        // live account - KeycloakAdminClient.resolveMirror) must not stop the whole run: it is
        // usually resolved by exactly that OTHER account's own sync, which restores its address.
        // So conflicts are skipped and retried once after everybody else has been synced.
        val conflicted = mutableListOf<Long>()
        existingAccountIds.forEach { accountId ->
            try {
                if (syncOne(accountId, existingAccountIds)) upserted++
            } catch (e: IllegalStateException) {
                conflicted += accountId
            }
        }
        val remaining = conflicted.filterNot { accountId ->
            try {
                if (syncOne(accountId, existingAccountIds)) upserted++
                true
            } catch (e: IllegalStateException) {
                log.warn("Keycloak full sync: account {} left unsynced - {}", accountId, e.message)
                false
            }
        }

        // Skipped (still emailless) accounts are deliberately absent from Keycloak - never treated
        // as orphans just because they haven't been upserted yet.
        val syncedAccountIds = keycloakAdminClient.findAllSyncedAccountIds()
        val orphans = syncedAccountIds - existingAccountIds
        orphans.forEach { keycloakAdminClient.deleteUser(it) }

        log.info(
            "Keycloak full sync: upserted {} account(s), deleted {} orphaned Keycloak user(s), {} conflict(s)",
            upserted, orphans.size, remaining.size
        )
        return KeycloakSyncResult(upserted = upserted, deletedOrphans = orphans.size, conflicts = remaining.size)
    }

    /** One account's mirror; `false` when there is nothing to mirror (no address yet). Throws on a conflict. */
    private fun syncOne(accountId: Long, existingAccountIds: Set<Long>): Boolean {
        val profile = accountService.findAccount(accountId) ?: return false
        // Nothing worth mirroring yet (REGISTER "Enrollment zuerst" account, freshly created,
        // docs/04-orchestrierung.md) - a Keycloak user needs an email/username; a later full
        // sync (or the event-driven path once one is confirmed) creates it for real.
        if (profile.email == null) {
            // A withdrawn address: the mirror, if any, must not keep presenting it.
            keycloakAdminClient.clearEmail(accountId)
            return false
        }
        // Unidentified account (REGISTER "Enrollment zuerst") - no person to look up yet.
        val person = profile.personId?.let { personMasterData.masterDataOf(it) }
        val mirror = kcUserMirror(
            profile, person,
            accountService.establishedClaimValues(profile.accountId, MIRRORED_CLAIM_TYPES)
        )
        keycloakAdminClient.upsertUser(
            accountId, profile.email, profile.emailConfirmed,
            mirror.firstName, mirror.lastName, mirror.attributes, accountExists = { it in existingAccountIds }
        )
        val keypair = accountKeypairService.keypairFor(accountId)
        val activeMethods = profile.activeAuthenticationMethods.map { it.method }.distinct()
        keycloakAdminClient.setPublicKeyCredential(accountId, keypair.publicKeyJwk, activeMethods)
        return true
    }
}
