package com.example.dpop.kcext;

import com.example.dpop.kcext.webtool.WebToolAvailability;
import org.jboss.logging.Logger;
import org.keycloak.authentication.AuthenticationFlowCallback;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.models.AuthenticationFlowModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.sessions.AuthenticationSessionModel;

import java.util.List;

/**
 * Runs first in {@code orchestrator-browser} (wrapped in its own tiny {@code
 * orchestrator-resume-wrapper} subflow purely so its {@link AuthenticationFlowCallback} actually
 * registers - see {@link #onTopFlowSuccess}'s own doc) - the one and only place that checks
 * whether this browser already carries a valid Keycloak identity cookie
 * ({@link OrchestratorNotes#resolveExistingUserSession}, the same check {@code auth-cookie} itself
 * does), and the one and only place that resubmits whatever RestoreData a PRIOR flow run stashed
 * on that session (docs/ideen/web-keycloak-kanal.md #6). The resolved UserSessionModel is used ONLY
 * for that - to resolve the account and to bind the RestoreData resubmission - never as the
 * peer-auth anchor itself, which is always {@code channelSessionId} (see
 * PeerAuthAssertionSigner's own doc on why: it keeps concurrent flows sharing the same underlying
 * SSO session, e.g. two tabs stepping up at once, from ever sharing an anchor value).
 *
 * Deliberately does NOTHING ELSE when no session exists yet (still a plain, anonymous flow run):
 * {@link KcChannelService#upsertChannel} always starts the entry journey on a channel's FIRST call
 * ({@code channelService.resumeChannel}, computing {@code KcSelectMethodStrategy}'s
 * {@code initialState} once and persisting it) - calling it here with no known account would bake
 * an empty/lookup-only candidate list into that journey before auth-username-password-form even
 * runs, and binding the account moments later (once LoA1's own authenticator learns it) does NOT
 * retroactively recompute that already-persisted state. Leaving the channel uncreated here means
 * LoA1/LoA2's own authenticator makes the channel's first, INFORMED call instead.
 *
 * Invisible: never renders a form, never fails the login on an orchestrator error (best-effort,
 * same reasoning as {@link OrchestratorNotes#stashRestoreDataAtFlowEnd}).
 */
public class OrchestratorResumeAuthenticator implements AuthenticationFlowCallback {

    private static final Logger LOG = Logger.getLogger(OrchestratorResumeAuthenticator.class);

    private final KeycloakSession session;
    private final OrchestratorClient client = new OrchestratorClient(
            OrchestratorConfig.BASE_URL, OrchestratorConfig.PEER_AUTH_ISSUER, OrchestratorConfig.PEER_AUTH_AUDIENCE
    );

    OrchestratorResumeAuthenticator(KeycloakSession session) {
        this.session = session;
    }

    @Override
    public void authenticate(AuthenticationFlowContext context) {
        try {
            AuthenticationSessionModel authSession = context.getAuthenticationSession();
            var existingUserSession = OrchestratorNotes.resolveExistingUserSession(context.getSession(), context.getRealm());
            if (existingUserSession != null) {
                // A fresh channel for THIS flow run - unrelated to existingUserSession's own id
                // (deliberately so, see OrchestratorNotes.channelSessionId's doc: reusing the SAME
                // id across separate flow runs is exactly the original BINDING_MISMATCH cause). The
                // link back to existingUserSession is carried entirely by `restoreData` below (bound
                // to existingUserSession's own id server-side) - never by the anchor, which is
                // always newChannelSessionId itself.
                String newChannelSessionId = OrchestratorNotes.channelSessionId(context);

                // context.getUser() is unset this early (auth-cookie, which resolves it, hasn't run
                // yet) - the UserSessionModel already carries its own UserModel independently of that,
                // and is the one reliable source at this exact point in the flow.
                Long accountId = OrchestratorNotes.accountId(existingUserSession.getUser());
                String restoreData = null;
                if (!"true".equals(authSession.getAuthNote(OrchestratorNotes.RESTORE_SUBMITTED))) {
                    restoreData = existingUserSession.getNote(OrchestratorNotes.USER_SESSION_NOTE_RESTORE_DATA);
                    authSession.setAuthNote(OrchestratorNotes.RESTORE_SUBMITTED, "true");
                }

                // Same reasoning as OrchestratorUpdateAuthenticator: raise the floor to whatever
                // Keycloak's own flow already knows it will require, not null, so an early proof
                // (restoreData resumed here) can't finish the journey below that level. No native
                // amr to resend either - this runs before any native authenticator in the flow, so
                // NATIVE_AMR is necessarily still empty at this point (see OrchestratorAuthenticator's
                // matching comment on why it never resends that note either). existingUserSession's
                // own id travels along ONLY as the RestoreData binding, never as the anchor itself.
                OrchestratorClient.ChannelResponse response = client.upsertChannel(
                        newChannelSessionId, accountId, OrchestratorNotes.requestedAcr(context), List.of(), restoreData, existingUserSession.getId(),
                        WebToolAvailability.renderableToolIds(context.getSession()), null
                );
                OrchestratorNotes.applyAuthData(authSession, response);
            }
        } catch (Exception e) {
            LOG.warnf(e, "OrchestratorResumeAuthenticator failed - continuing without a resumed channel");
        }
        context.success();
    }

    @Override
    public void action(AuthenticationFlowContext context) {
        context.success();
    }

    @Override
    public void onParentFlowSuccess(AuthenticationFlowContext context) {
        // Fires the moment orchestrator-resume-wrapper (this authenticator's OWN, otherwise
        // pointless wrapper subflow) completes - which is immediately, right after authenticate()
        // above, at the very START of the overall flow. Too early for RestoreData (no proof from
        // THIS flow run exists yet) - onTopFlowSuccess below is the actual end-of-flow hook this
        // wrapper exists to make reachable at all.
    }

    @Override
    public void onTopFlowSuccess(AuthenticationFlowModel topFlow) {
        // Fires once, at the true end of the WHOLE top-level flow (docs/ideen/web-keycloak-
        // kanal.md #6) - after LoA-1/LoA-2, whichever ran, are already done. No AuthenticationFlowContext
        // available here (only the flow model) - session.getContext().getAuthenticationSession()
        // is the same pattern Keycloak's own ConditionalLoaAuthenticator.onTopFlowSuccess uses.
        AuthenticationSessionModel authSession = session.getContext().getAuthenticationSession();
        if (authSession == null) return;
        OrchestratorNotes.stashRestoreDataAtFlowEnd(session, authSession, client, LOG);
    }

    @Override
    public boolean requiresUser() {
        return false;
    }

    @Override
    public boolean configuredFor(KeycloakSession session, RealmModel realm, UserModel user) {
        return true;
    }

    @Override
    public void setRequiredActions(KeycloakSession session, RealmModel realm, UserModel user) {
    }

    @Override
    public void close() {
    }
}
