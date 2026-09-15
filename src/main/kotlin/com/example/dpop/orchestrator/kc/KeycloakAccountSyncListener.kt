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
 * Keeps a Keycloak user mirrored for every orchestrator account
 * - no `infra/tofu/keycloak/main.tf`-declared demo users needed: whatever
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
    private val keycloakAdminClient: KeycloakAdminClient,
    private val accountKeypairService: AccountKeypairService,
    private val accountKeycloakKeypairRepository: AccountKeycloakKeypairRepository
) {
    private val log = LoggerFactory.getLogger(KeycloakAccountSyncListener::class.java)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onAccountChanged(event: AccountChanged) {
        val profile = accountService.findAccount(event.accountId) ?: return
        // Nothing worth mirroring yet (REGISTER "Enrollment zuerst" account, freshly created,
        // docs/04-orchestrierung.md) - a Keycloak user needs an email/username; the next
        // AccountChanged once one is confirmed (or the account is identified) syncs it for real.
        if (profile.email == null) return
        // Unidentified account (REGISTER "Enrollment zuerst") - no person to look up yet.
        val person = profile.personId?.let { extStammdatenService.findPersonById(it) }
        try {
            keycloakAdminClient.upsertUser(
                profile.accountId, profile.email, profile.emailConfirmed,
                person?.vorname ?: UNIDENTIFIED_FIRST_NAME, person?.name ?: UNIDENTIFIED_LAST_NAME
            )
            val keypair = accountKeypairService.keypairFor(profile.accountId)
            val activeMethods = profile.activeAuthenticationMethods.map { it.method }.distinct()
            keycloakAdminClient.setPublicKeyCredential(profile.accountId, keypair.publicKeyJwk, activeMethods)
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
        // Local-only, no Keycloak round-trip needed - safe to do even if the deleteUser() call
        // above failed, unlike deleteUser() itself this can't leave anything orphaned on the
        // Keycloak side. deleteById() would throw if no row exists (e.g. non-keycloak-synced
        // account) - existsById() guard keeps this a true no-op then.
        if (accountKeycloakKeypairRepository.existsById(event.accountId)) {
            accountKeycloakKeypairRepository.deleteById(event.accountId)
        }
    }
}

/**
 * Keycloak's own realm requires a non-blank first/last name on every user - an unidentified
 * account (REGISTER "Enrollment zuerst") has no [com.example.dpop.ext_stammdaten.Person] to take
 * them from yet, so this stands in until one exists. Self-healing: `AccountService.bindPersonId`
 * fires its own `AccountChanged`, which re-syncs and overwrites this with the real name the moment
 * the account is identified - never a value anyone needs to clean up by hand.
 */
internal const val UNIDENTIFIED_FIRST_NAME = "Unbekannt"
internal const val UNIDENTIFIED_LAST_NAME = "(nicht identifiziert)"
