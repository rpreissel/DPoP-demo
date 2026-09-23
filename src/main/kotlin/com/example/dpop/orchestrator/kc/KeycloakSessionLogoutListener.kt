package com.example.dpop.orchestrator.kc

import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * An APP channel logged out and held a real Keycloak session of its own (the custom account-token
 * grant's reused session, `AuthContext.keycloakSessionId`) - that one session, and only it, has to
 * end in Keycloak too.
 *
 * An event rather than a direct call: the logout transition runs inside `JourneyService`'s
 * transaction, and calling the Admin API from there held that transaction open across a network
 * round trip - exactly what [KeycloakAccountSyncListener] states as the rule and does not do.
 * [TransactionPhase.AFTER_COMMIT] additionally means Keycloak is never asked to end a session for a
 * logout that then rolled back.
 *
 * Deliberately a plain `@TransactionalEventListener` and NOT an `@ApplicationModuleListener`,
 * unlike the account sync next to it - so a failure here is logged and dropped rather than kept in
 * the Event Publication Registry for retry.
 *
 * The difference is what a failure leaves behind. A missing Keycloak user is a lasting
 * inconsistency: the account exists, cannot log in, and nothing repairs itself. A session that was
 * not ended early expires on its own within minutes, so a retry would mostly be chasing a session
 * that is already gone - and a permanently failing one would sit in the registry forever.
 */
@Component
@Profile("keycloak")
class KeycloakSessionLogoutListener(private val keycloakAdminClient: KeycloakAdminClient) {

    private val log = LoggerFactory.getLogger(KeycloakSessionLogoutListener::class.java)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onChannelLoggedOut(event: KeycloakSessionEnded) {
        runCatching { keycloakAdminClient.logoutSession(event.keycloakSessionId) }
            .onFailure { log.warn("Keycloak session logout failed for {}", event.keycloakSessionId, it) }
    }
}

/**
 * "This channel's own Keycloak session is over." Published by the logout transition, acted on after
 * that transition commits.
 *
 * Carries only the session id: which channel it belonged to is already decided by the publisher
 * (APP channels only - a WEB channel's logout stays entirely Keycloak's own, docs/07-betrieb.md
 * Abschnitt 3), and re-deciding it here would put that rule in two places.
 */
data class KeycloakSessionEnded(val keycloakSessionId: String)
