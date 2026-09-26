package com.example.dpop.kcext;

import org.jboss.logging.Logger;
import org.keycloak.Config;
import org.keycloak.events.Event;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.EventListenerProviderFactory;
import org.keycloak.events.EventType;
import org.keycloak.events.admin.AdminEvent;
import org.keycloak.models.AbstractKeycloakTransaction;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.RealmModel;
import org.keycloak.storage.StorageId;

/**
 * Reports every Keycloak logout of an orchestrator account to the orchestrator, for the account's
 * sign-in log (ADR-39, addendum): the Web channel's logout is Keycloak's own, so the orchestrator
 * would otherwise know only the logouts it performs itself.
 *
 * <p>Sent after Keycloak's own transaction committed, and fire-and-forget: a logout never waits on
 * the orchestrator and never fails because of it - a lost report costs one log line, a failed
 * logout would cost the user. Only users of the orchestrator's user storage count; anyone else
 * Keycloak knows (its admin) has no account to log for.
 */
public class SignInLogEventListener implements EventListenerProvider {

    private static final Logger LOG = Logger.getLogger(SignInLogEventListener.class);

    private final KeycloakSession session;

    SignInLogEventListener(KeycloakSession session) {
        this.session = session;
    }

    @Override
    public void onEvent(Event event) {
        if (event.getType() != EventType.LOGOUT || event.getUserId() == null || event.getSessionId() == null) {
            return;
        }
        RealmModel realm = session.realms().getRealm(event.getRealmId());
        var component = realm == null ? null : OrchestratorStorageProviderFactory.componentIn(realm).orElse(null);
        if (component == null || !component.getId().equals(StorageId.providerId(event.getUserId()))) {
            return;
        }
        long accountId;
        try {
            accountId = Long.parseLong(StorageId.externalId(event.getUserId()));
        } catch (NumberFormatException e) {
            return;
        }
        OrchestratorClient client = OrchestratorSettings.from(component).newClient();
        String kcSessionId = event.getSessionId();
        session.getTransactionManager().enlistAfterCompletion(new AbstractKeycloakTransaction() {
            @Override
            protected void commitImpl() {
                try {
                    client.reportSignOut(accountId, kcSessionId);
                } catch (Exception e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    LOG.warnf("Could not report the logout of account %d to the orchestrator: %s", accountId, e.getMessage());
                }
            }

            @Override
            protected void rollbackImpl() {
                // The logout did not happen - nothing to report.
            }
        });
    }

    @Override
    public void onEvent(AdminEvent event, boolean includeRepresentation) {
        // Admin actions are no sign-outs of the holder.
    }

    @Override
    public void close() {
    }

    /** Registered as {@value #ID}; the realm lists it among its event listeners (keycloak-migrations V3). */
    public static class Factory implements EventListenerProviderFactory {
        public static final String ID = "orchestrator-sign-in-log";

        @Override
        public EventListenerProvider create(KeycloakSession session) {
            return new SignInLogEventListener(session);
        }

        @Override
        public void init(Config.Scope config) {
        }

        @Override
        public void postInit(KeycloakSessionFactory factory) {
        }

        @Override
        public void close() {
        }

        @Override
        public String getId() {
            return ID;
        }
    }
}
