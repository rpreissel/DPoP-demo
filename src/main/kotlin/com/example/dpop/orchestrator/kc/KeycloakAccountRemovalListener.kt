package com.example.dpop.orchestrator.kc

import com.example.dpop.account.AccountDeleted
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import org.springframework.modulith.events.ApplicationModuleListener

/**
 * The one thing Keycloak still has to hear about an account: that it is gone (review 2026-09, P-3).
 * Everything else Keycloak reads through its federation on demand - there is no mirror to keep in
 * step, and no event per account change. A deleted account leaves Keycloak-local state behind
 * (sessions, login failures, consents), which [KeycloakAdminClient.removeAccount] clears.
 *
 * [ApplicationModuleListener]: AFTER_COMMIT, asynchronous, and recorded in the Event Publication
 * Registry - a failed removal stays an incomplete publication and is retried, instead of silently
 * leaving a deleted account's sessions alive (docs/07-betrieb.md Abschnitt 3a). On Spring's shared
 * async pool: a removal is idempotent and independent of every other, so neither order nor a lane
 * of its own matters (the single-thread lane of the former mirror is gone, review 2026-09-26, A-1).
 */
@Component
@Profile("keycloak")
class KeycloakAccountRemovalListener(
    private val keycloakAdminClient: KeycloakAdminClient,
) {
    /** A failed removal throws, leaving this listener's publication incomplete - and so retried. */
    @ApplicationModuleListener
    fun onAccountDeleted(event: AccountDeleted) {
        keycloakAdminClient.removeAccount(event.accountId)
    }
}
