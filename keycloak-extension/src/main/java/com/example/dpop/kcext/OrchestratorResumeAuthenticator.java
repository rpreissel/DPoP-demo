package com.example.dpop.kcext;

import org.jboss.logging.Logger;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.Authenticator;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.sessions.AuthenticationSessionModel;

import java.util.List;

/**
 * Runs first in {@code orchestrator-browser}, before {@code auth-cookie} - the one and only place
 * that checks whether this browser already carries a valid Keycloak identity cookie
 * ({@link OrchestratorNotes#resolveExistingUserSession}, the same check {@code auth-cookie} itself
 * does), and the one and only place that resubmits whatever RestoreData a PRIOR flow run stashed
 * on that session (docs/ideen/web-keycloak-kanal.md #6). Every later authenticator in this run
 * reads the anchor it records ({@link OrchestratorNotes#recordAnchor}) back via
 * {@link OrchestratorNotes#anchorEstablished}/{@link OrchestratorNotes#readAnchor} instead of
 * re-checking the cookie or resubmitting RestoreData itself.
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
 * same reasoning as {@link OrchestratorRestoreDataListener}).
 */
public class OrchestratorResumeAuthenticator implements Authenticator {

    private static final Logger LOG = Logger.getLogger(OrchestratorResumeAuthenticator.class);

    private final OrchestratorClient client = new OrchestratorClient(
            OrchestratorConfig.BASE_URL, OrchestratorConfig.PEER_AUTH_ISSUER, OrchestratorConfig.PEER_AUTH_AUDIENCE
    );

    @Override
    public void authenticate(AuthenticationFlowContext context) {
        try {
            AuthenticationSessionModel authSession = context.getAuthenticationSession();
            var existingUserSession = OrchestratorNotes.resolveExistingUserSession(context);
            if (existingUserSession != null) {
                // No context.attachUserSession(...) here - that is auth-cookie's own job, and
                // auth-cookie runs right after this in the same flow. We only need the id, to know
                // which channel/RestoreData to resume; attaching the session to the PROCESSOR is a
                // concern this authenticator has no reason to take on.

                // A fresh channel for THIS flow run - unrelated to existingUserSession's own id
                // (deliberately so, see OrchestratorNotes.channelSessionId's doc: reusing the SAME
                // id across separate flow runs is exactly the original BINDING_MISMATCH cause). The
                // link back to existingUserSession is carried entirely by `kcSessionId` and
                // `restoreData` below, resolved server-side once upsertChannel receives them - not
                // by any equality between newChannelSessionId and anything on existingUserSession.
                String newChannelSessionId = OrchestratorNotes.channelSessionId(context);
                context.getEvent().detail(OrchestratorNotes.CHANNEL_SESSION_ID, newChannelSessionId);
                String kcSessionId = existingUserSession.getId();
                OrchestratorNotes.recordAnchor(context, kcSessionId);

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
                // matching comment on why it never resends that note either).
                OrchestratorClient.ChannelResponse response = client.upsertChannel(
                        newChannelSessionId, kcSessionId, accountId, OrchestratorNotes.requestedAcr(context), List.of(), restoreData
                );
                if (response.authDataAcr() != null) {
                    authSession.setUserSessionNote(OrchestratorNotes.USER_SESSION_NOTE_ACR, response.authDataAcr());
                }
                if (!response.authDataAmr().isEmpty()) {
                    authSession.setUserSessionNote(OrchestratorNotes.USER_SESSION_NOTE_AMR, String.join(",", response.authDataAmr().keySet()));
                }
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
