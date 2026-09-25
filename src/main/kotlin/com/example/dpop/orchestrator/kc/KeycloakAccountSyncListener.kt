package com.example.dpop.orchestrator.kc

import com.example.dpop.account.AccountDeleted
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.scheduling.annotation.Async

/**
 * The one thing Keycloak still has to hear about an account: that it is gone (review 2026-09, P-3).
 * Everything else Keycloak reads through its federation on demand - there is no mirror to keep in
 * step, and no event per account change. A deleted account leaves Keycloak-local state behind
 * (sessions, login failures, consents), which [KeycloakAdminClient.removeAccount] clears.
 *
 * [ApplicationModuleListener]: AFTER_COMMIT, asynchronous, and recorded in the Event Publication
 * Registry - a failed removal stays an incomplete publication and is retried, instead of silently
 * leaving a deleted account's sessions alive (docs/07-betrieb.md Abschnitt 3a).
 */
@Component
@Profile("keycloak")
class KeycloakAccountSyncListener(
    private val keycloakAdminClient: KeycloakAdminClient,
    private val accountKeycloakKeypairRepository: AccountKeycloakKeypairRepository
) {
    @ApplicationModuleListener
    @Async(KEYCLOAK_SYNC_EXECUTOR)
    fun onAccountDeleted(event: AccountDeleted) {
        // Local first, remote second. The local row can never be orphaned by removing it early
        // (nothing outside this process reads it), while a failed removal must be retried - and
        // is, because the exception leaves this listener's publication incomplete. On that retry
        // the existsById() guard makes the local half a true no-op.
        if (accountKeycloakKeypairRepository.existsById(event.accountId)) {
            accountKeycloakKeypairRepository.deleteById(event.accountId)
        }
        keycloakAdminClient.removeAccount(event.accountId)
    }
}
