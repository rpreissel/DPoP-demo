package com.example.dpop.kcext;

import org.jboss.logging.Logger;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.authentication.Authenticator;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.sessions.AuthenticationSessionModel;

/**
 * Placed directly after a native step that can prove something itself (docs/ideen/
 * web-keycloak-kanal.md #9) - reports that proof to the orchestrator immediately, without
 * rendering anything of its own. Renders no form: {@link #authenticate} always resolves the step
 * (success or, on an orchestrator-side rejection, failure) without ever calling
 * {@link AuthenticationFlowContext#challenge}.
 *
 * Config: {@code nativeToolId} - the stable, per-authenticator-TYPE id a
 * {@code NativeAuthenticatorDescriptor} on the orchestrator side resolves method/loa/factorTypes
 * from (docs/ideen/web-keycloak-kanal.md #6). {@code amrSourceId} defaults to this execution's own
 * id if left empty - stable across a browser retry of the SAME execution, which is exactly the
 * "refresh, not a new proof" behaviour Section 6 asks for.
 */
public class OrchestratorUpdateAuthenticator implements Authenticator {

    private static final Logger LOG = Logger.getLogger(OrchestratorUpdateAuthenticator.class);

    private final OrchestratorClient client = new OrchestratorClient(
            OrchestratorConfig.BASE_URL, OrchestratorConfig.PEER_AUTH_ISSUER, OrchestratorConfig.PEER_AUTH_AUDIENCE
    );

    @Override
    public void authenticate(AuthenticationFlowContext context) {
        try {
            AuthenticationSessionModel authSession = context.getAuthenticationSession();
            String nativeToolId = context.getAuthenticatorConfig() == null ? null
                    : context.getAuthenticatorConfig().getConfig().get("nativeToolId");
            if (nativeToolId == null || nativeToolId.isBlank()) {
                LOG.warn("OrchestratorUpdateAuthenticator has no nativeToolId configured - skipping");
                context.success();
                return;
            }
            String configuredSourceId = context.getAuthenticatorConfig().getConfig().get("amrSourceId");
            String amrSourceId = configuredSourceId != null && !configuredSourceId.isBlank()
                    ? configuredSourceId
                    : context.getExecution().getId();

            OrchestratorNotes.appendNativeAmr(context, nativeToolId, amrSourceId);

            String channelSessionId = OrchestratorNotes.channelSessionId(context);
            // context.getUser() can already be set here even on an initial-login anchor run - a
            // native authenticator ahead of this one (e.g. auth-username-password-form)
            // may have resolved it from Keycloak's own credential store, independently of any
            // orchestrator tool (docs/ideen/web-keycloak-kanal.md #6: "falls Keycloak den Nutzer
            // schon kennt" is not step-up-only).
            Long accountId = OrchestratorNotes.accountId(context.getUser());
            // The channel's floor must reach the FULL level Keycloak's own flow will eventually
            // require, not just "loa1" (this authenticator's own tier) - see requestedAcr's doc:
            // otherwise this call's own password report can finish the entry journey before a
            // later Condition-LoA-2 subflow ever gets a chance to raise the floor further.
            String targetAcr = OrchestratorNotes.requestedAcr(context);

            OrchestratorClient.ChannelResponse response = client.upsertChannel(
                    channelSessionId, accountId, targetAcr, OrchestratorNotes.nativeAmr(context), null, null
            );
            if (response.authDataAcr() != null) {
                authSession.setUserSessionNote(OrchestratorNotes.USER_SESSION_NOTE_ACR, response.authDataAcr());
            }
            if (!response.authDataAmr().isEmpty()) {
                authSession.setUserSessionNote(OrchestratorNotes.USER_SESSION_NOTE_AMR, String.join(",", response.authDataAmr().keySet()));
            }
            context.success();
        } catch (Exception e) {
            LOG.error("OrchestratorUpdateAuthenticator failed", e);
            context.failure(AuthenticationFlowError.INTERNAL_ERROR);
        }
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
