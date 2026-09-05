package com.example.dpop.kcext;

import org.jboss.logging.Logger;
import org.keycloak.events.Event;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.EventType;
import org.keycloak.events.admin.AdminEvent;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.models.UserSessionProvider;

/**
 * The Section 6 "end-of-flow lifecycle hook", as a login EventListener rather than a method on
 * {@link OrchestratorAuthenticator} itself. For a step-up, {@code context.getUserSession()} is
 * already there mid-flow - Keycloak's own cookie authenticator sets its acr/amr notes exactly that
 * way - but on a fresh, first-time login there is no UserSessionModel yet during the flow at all;
 * {@link org.keycloak.authentication.AuthenticationManager} only creates one once every
 * authenticator has already succeeded. The {@code LOGIN} event is what fires right after that, with
 * both the fresh {@code sessionId} and every auth-note this flow run wrote via
 * {@code setUserSessionNote} already copied onto it. Using the event uniformly for both cases (not
 * just the first-login one) keeps this file the one place that stashes RestoreData, instead of
 * splitting that logic between here and the authenticator. Stashes it on the UserSessionModel - not
 * the auth session, which is gone once the flow ends - for a LATER, independent flow run (a
 * step-up) to resubmit (docs/ideen/web-keycloak-kanal.md #6).
 */
public class OrchestratorRestoreDataListener implements EventListenerProvider {

    private static final Logger LOG = Logger.getLogger(OrchestratorRestoreDataListener.class);

    private final KeycloakSession session;
    private final OrchestratorClient client = new OrchestratorClient(
            OrchestratorConfig.BASE_URL, OrchestratorConfig.PEER_AUTH_ISSUER, OrchestratorConfig.PEER_AUTH_AUDIENCE
    );

    OrchestratorRestoreDataListener(KeycloakSession session) {
        this.session = session;
    }

    @Override
    public void onEvent(Event event) {
        if (event.getType() != EventType.LOGIN) return;
        var details = event.getDetails();
        String channelSessionId = details == null ? null : details.get(OrchestratorNotes.CHANNEL_SESSION_ID);
        if (channelSessionId == null || event.getSessionId() == null) return; // Not a Keycloak-channel login run.

        // The anchor whichever authenticator ran this flow actually called upsertChannel with -
        // for a first-time login this is authSession.getParentSession().getId(), which is exactly
        // the id Keycloak just assigned this UserSessionModel (event.getSessionId()); for a
        // step-up it is that same existing UserSessionModel's own id. Either way it now equals
        // event.getSessionId(), but reading it back here (rather than assuming that equality)
        // still needs a recorded anchor to exist at all - nothing to restore otherwise.
        String anchorValue = details.get(OrchestratorNotes.ANCHOR_VALUE);
        if (anchorValue == null) return;

        try {
            String restoreData = client.restoreData(channelSessionId, anchorValue);
            if (restoreData == null) return;

            RealmModel realm = session.realms().getRealm(event.getRealmId());
            UserSessionProvider sessions = session.sessions();
            UserSessionModel userSession = sessions.getUserSession(realm, event.getSessionId());
            if (userSession != null) {
                userSession.setNote(OrchestratorNotes.USER_SESSION_NOTE_RESTORE_DATA, restoreData);
            }
        } catch (Exception e) {
            // Best-effort: a missed RestoreData write only means a later step-up starts without a
            // running start (docs/ideen/web-keycloak-kanal.md #6), never a broken login.
            LOG.warnf(e, "Failed to fetch/stash RestoreData for channel %s", channelSessionId);
        }
    }

    @Override
    public void onEvent(AdminEvent event, boolean includeRepresentation) {
    }

    @Override
    public void close() {
    }
}
