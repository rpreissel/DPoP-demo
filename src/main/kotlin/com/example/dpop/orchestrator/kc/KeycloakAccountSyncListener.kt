package com.example.dpop.orchestrator.kc

import com.example.dpop.account.AccountChanged
import com.example.dpop.account.AccountDeleted
import com.example.dpop.account.AccountService
import com.example.dpop.ext_stammdaten.ExtStammdatenService
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * Keeps a Keycloak user mirrored for every orchestrator account (docs/ideen/web-keycloak-kanal.md)
 * - replaces the earlier `infra/tofu/keycloak/main.tf`-declared demo users entirely: whatever
 * `KcDemoAccountSeeder` (or any real account creation) does to an account, this reacts to and
 * pushes to Keycloak via [KeycloakAdminClient]. Only registered under the `keycloak` Spring
 * profile - deciding whether this mechanism runs at all is a startup-time concern (`@Profile`),
 * not a runtime toggle.
 *
 * [TransactionPhase.AFTER_COMMIT]: never sync a change that might still roll back, and never hold
 * the account's own DB transaction open across a network call to Keycloak.
 *
 * Best-effort by design: a failed sync is logged, not rethrown - it must never turn an orchestrator
 * account mutation (already committed by the time this runs) into a failed request. A later
 * [AccountChanged] for the same account (or a manual retry) is the recovery path, not an
 * automatic one.
 */
@Component
@Profile("keycloak")
class KeycloakAccountSyncListener(
    private val accountService: AccountService,
    private val extStammdatenService: ExtStammdatenService,
    private val keycloakAdminClient: KeycloakAdminClient
) {
    private val log = LoggerFactory.getLogger(KeycloakAccountSyncListener::class.java)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onAccountChanged(event: AccountChanged) {
        val profile = accountService.findAccount(event.accountId) ?: return
        val person = extStammdatenService.findPersonById(profile.personId)
        try {
            keycloakAdminClient.upsertUser(profile.accountId, profile.email, person?.vorname, person?.name)
        } catch (e: Exception) {
            log.warn("Keycloak account sync failed for accountId={}", event.accountId, e)
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onAccountDeleted(event: AccountDeleted) {
        try {
            keycloakAdminClient.deleteUser(event.accountId)
        } catch (e: Exception) {
            log.warn("Keycloak account sync (delete) failed for accountId={}", event.accountId, e)
        }
    }
}
