package com.example.dpop.kcext;

import jakarta.ws.rs.core.MultivaluedMap;
import org.jboss.logging.Logger;
import org.keycloak.authentication.InitiatedActionSupport;
import org.keycloak.authentication.RequiredActionContext;
import org.keycloak.authentication.RequiredActionProvider;
import org.keycloak.sessions.AuthenticationSessionModel;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Web-Kanal-Selbstbedienung "Anmeldeverfahren verwalten" (docs/ideen/
 * manage-auth-methods-im-web-kanal.md) - reachable via {@code kc_action=orchestrator-manage-methods}
 * on the EXISTING login client/flow (V2, unchanged): Keycloak's own mechanism for "an already
 * authenticated user triggers a self-service action", same idiom as its built-in "update
 * password"/"configure OTP" links. Runs after {@code orchestrator-browser} already completed -
 * {@link OrchestratorResumeAuthenticator}, first in that flow, already brought this run's channel
 * to {@code AUTHENTICATED} via {@code restoreData} if the SSO session's prior evidence still
 * covers it, so no second login happens here.
 *
 * Landing screen is the active-methods LIST ({@code GET .../methods}), not straight into
 * enrollment - "manage" covers add AND remove, so both live behind the same list: each active
 * method has its own "Entfernen" button, plus one "Neues Verfahren hinzufügen" button and a
 * "Fertig" button to actually leave. Either action re-shows the (freshly re-fetched) list with a
 * status line afterwards rather than silently finishing the whole required action - silently
 * disappearing after "nothing was available to add" left no feedback at all, and a required
 * action doesn't get to redirect back into the SPA on its own to show one.
 *
 * Calls the already facade-neutral {@code POST .../channels/{id}/enrollments}/
 * {@code DELETE .../channels/{id}/methods/{id}} (same entry points the App channel uses for
 * {@code MANAGE_AUTH_METHODS}) and renders whatever {@code next} comes back via the same
 * {@link WebToolRenderer}/{@code WebFormRenderer} dispatch {@link OrchestratorAuthenticator} uses
 * - deliberately a SEPARATE, small dispatch loop rather than a shared one:
 * {@link RequiredActionContext} has no {@code setUser}, and its {@code failure()} takes no
 * {@code AuthenticationFlowError}, diverging enough from {@code AuthenticationFlowContext} that
 * forcing one shared method would cost more than it saves. Only the actual form-building
 * ({@link WebFormRenderer}) is shared.
 */
public class OrchestratorManageMethodsRequiredAction implements RequiredActionProvider {

    static final String PROVIDER_ID = "orchestrator-manage-methods";

    /** "add" or "remove" - which action the current select/tool sub-journey serves, so its completion can phrase the right status line. Local to this class; OrchestratorAuthenticator's own PENDING_KIND notes never see this key. */
    private static final String PENDING_ACTION = "orchestrator_manage_pending_action";

    private static final Logger LOG = Logger.getLogger(OrchestratorManageMethodsRequiredAction.class);

    private final OrchestratorClient client = new OrchestratorClient(
            OrchestratorConfig.BASE_URL, OrchestratorConfig.PEER_AUTH_ISSUER, OrchestratorConfig.PEER_AUTH_AUDIENCE
    );

    @Override
    public void evaluateTriggers(RequiredActionContext context) {
        // Nothing to evaluate - this action is never auto-triggered (defaultAction=false in the
        // registering migration), only ever reached explicitly via kc_action.
    }

    /**
     * The default is {@code NOT_SUPPORTED} - without this override, Keycloak rejects the
     * {@code kc_action=orchestrator-manage-methods} request outright (silent
     * {@code kc_action_status=error} in the redirect, never even calling this provider) before
     * {@link #requiredActionChallenge} ever runs. This is exactly the "user-initiated" case the
     * flag exists for.
     */
    @Override
    public InitiatedActionSupport initiatedActionSupport() {
        return InitiatedActionSupport.SUPPORTED;
    }

    @Override
    public void requiredActionChallenge(RequiredActionContext context) {
        try {
            String channelSessionId = OrchestratorNotes.channelSessionId(context.getAuthenticationSession());
            renderList(context, channelSessionId, null);
        } catch (OrchestratorClient.OrchestratorApiException e) {
            LOG.warnf("getMethods failed: %s", e.getMessage());
            context.challenge(WebFormRenderer.errorForm(context.form(), context.getAuthenticationSession(),
                    "Methodenverwaltung derzeit nicht möglich."));
        } catch (Exception e) {
            LOG.error("OrchestratorManageMethodsRequiredAction.requiredActionChallenge failed", e);
            context.failure();
        }
    }

    @Override
    public void processAction(RequiredActionContext context) {
        try {
            AuthenticationSessionModel authSession = context.getAuthenticationSession();
            String channelSessionId = OrchestratorNotes.channelSessionId(authSession);
            MultivaluedMap<String, String> form = context.getHttpRequest().getDecodedFormParameters();
            String pendingKind = authSession.getAuthNote(OrchestratorNotes.PENDING_KIND);

            if ("list".equals(pendingKind)) {
                if ("done".equals(form.getFirst("action"))) {
                    context.success();
                    return;
                }
                if ("add".equals(form.getFirst("action"))) {
                    authSession.setAuthNote(PENDING_ACTION, "add");
                    handleResponse(context, client.startEnrollments(channelSessionId), true);
                    return;
                }
                String methodInstanceId = form.getFirst("removeMethodInstanceId");
                if (methodInstanceId != null && !methodInstanceId.isBlank()) {
                    authSession.setAuthNote(PENDING_ACTION, "remove");
                    handleResponse(context, client.deactivateMethod(channelSessionId, methodInstanceId), true);
                    return;
                }
                renderList(context, channelSessionId, null);
                return;
            }

            OrchestratorClient.ChannelResponse response;
            if ("select".equals(pendingKind)) {
                if ("true".equals(form.getFirst("orchestrator_abandon"))) {
                    // Cancels whichever journey is currently active here - the step-up sub-journey
                    // (gate()) or the top-level MANAGE_AUTH_METHODS journey itself (candidate
                    // choice) - either way it resolves back to AUTHENTICATED (cancelledTo), so this
                    // always lands on the list. Rendered directly with its own notice rather than
                    // through handleResponse: that method's ADD/REMOVE phrasing ("hinzugefügt"/
                    // "entfernt") would misreport an abandoned attempt as a completed one.
                    client.abandonJourney(channelSessionId);
                    renderList(context, channelSessionId, "Abgebrochen.");
                    return;
                }
                String selectedToolId = form.getFirst("toolId");
                if (selectedToolId == null || selectedToolId.isBlank()) {
                    context.challenge(WebFormRenderer.errorForm(context.form(), authSession, "Bitte eine Methode auswählen."));
                    return;
                }
                response = client.activateTool(channelSessionId, selectedToolId);
            } else {
                String toolId = authSession.getAuthNote(OrchestratorNotes.PENDING_TOOL_ID);
                String toolSessionId = authSession.getAuthNote(OrchestratorNotes.PENDING_TOOL_SESSION_ID);
                if (toolId == null || toolSessionId == null) {
                    context.failure();
                    return;
                }
                if ("true".equals(form.getFirst("orchestrator_abandon"))) {
                    response = client.abandonTool(channelSessionId, toolSessionId, toolId);
                } else {
                    Map<String, String> fields = new LinkedHashMap<>();
                    form.forEach((key, values) -> {
                        if (!key.startsWith("orchestrator_") && !values.isEmpty()) fields.put(key, values.get(0));
                    });
                    response = client.patchTool(channelSessionId, toolSessionId, toolId, fields);
                }
            }
            handleResponse(context, response, false);
        } catch (OrchestratorClient.OrchestratorApiException e) {
            LOG.infof("Orchestrator tool call failed: %s", e.getMessage());
            context.challenge(WebFormRenderer.errorForm(context.form(), context.getAuthenticationSession(),
                    e.message != null ? e.message : "Anmeldung derzeit nicht möglich."));
        } catch (Exception e) {
            LOG.error("OrchestratorManageMethodsRequiredAction.processAction failed", e);
            context.failure();
        }
    }

    private void renderList(RequiredActionContext context, String channelSessionId, String notice) throws Exception {
        List<OrchestratorClient.MethodView> methods = client.getMethods(channelSessionId);
        AuthenticationSessionModel authSession = context.getAuthenticationSession();
        authSession.setAuthNote(OrchestratorNotes.PENDING_KIND, "list");
        context.challenge(WebFormRenderer.methodsListForm(context.form(), authSession, methods, notice));
    }

    /**
     * @param firstCall true only right after {@code startEnrollments}/{@code deactivateMethod}'s
     *                  own first call (before any select/tool step of this attempt ever rendered)
     *                  - distinguishes "nothing was available/needed" from "a sub-journey just
     *                  finished" for the status line below, since both end in the exact same
     *                  {@code channelState=AUTHENTICATED, next=null} response shape.
     */
    private void handleResponse(RequiredActionContext context, OrchestratorClient.ChannelResponse response, boolean firstCall) throws Exception {
        AuthenticationSessionModel authSession = context.getAuthenticationSession();
        String channelSessionId = OrchestratorNotes.channelSessionId(authSession);

        OrchestratorNotes.applyAuthData(authSession, response);

        // Deliberately NOT also checking channelState=="AUTHENTICATED" the way
        // OrchestratorAuthenticator's own handleResponse does: that shortcut only holds for
        // login/step-up, where AUTHENTICATED is the JOURNEY'S OUTCOME. Here the channel is
        // AUTHENTICATED from before MANAGE_AUTH_METHODS even starts (it's the precondition, per
        // docs/04-orchestrierung.md), so it stays true through the entire add/remove sub-journey -
        // checking it here fired on the very first response, before any candidate/tool step ever
        // rendered, which is exactly why every enroll-qr attempt looked like "nothing available".
        OrchestratorClient.Next next = response.next();
        if (next == null || next.isAuthenticated()) {
            String action = authSession.getAuthNote(PENDING_ACTION);
            String notice = "add".equals(action)
                    ? (firstCall ? "Keine weiteren Anmeldeverfahren verfügbar." : "Anmeldeverfahren hinzugefügt.")
                    : "remove".equals(action) ? "Anmeldeverfahren entfernt." : null;
            renderList(context, channelSessionId, notice);
            return;
        }

        if (next.isSelectMethod()) {
            List<String> options = response.stepDataOptions();
            if (options.isEmpty()) {
                renderList(context, channelSessionId, "Keine weiteren Anmeldeverfahren verfügbar.");
                return;
            }
            authSession.setAuthNote(OrchestratorNotes.PENDING_KIND, "select");
            context.challenge(WebFormRenderer.selectForm(context.getSession(), context.form(), authSession, options, response, null));
            return;
        }

        if (next.isTool()) {
            if (next.toolSessionId() == null) {
                // Single-candidate auto-activation, same as OrchestratorAuthenticator's own case.
                try {
                    OrchestratorClient.ChannelResponse activated = client.activateTool(channelSessionId, next.toolId());
                    handleResponse(context, activated, false);
                } catch (OrchestratorClient.OrchestratorApiException e) {
                    LOG.warnf("Auto-activation of '%s' failed: %s", next.toolId(), e.getMessage());
                    context.challenge(WebFormRenderer.errorForm(context.form(), authSession, "Anmeldung derzeit nicht möglich."));
                }
                return;
            }
            authSession.setAuthNote(OrchestratorNotes.PENDING_KIND, "tool");
            authSession.setAuthNote(OrchestratorNotes.PENDING_TOOL_ID, next.toolId());
            authSession.setAuthNote(OrchestratorNotes.PENDING_TOOL_SESSION_ID, next.toolSessionId());
            context.challenge(WebFormRenderer.toolForm(context.getSession(), context.form(), authSession, next, response, null));
            return;
        }

        LOG.warnf("Unhandled orchestrator next: type=%s step=%s", next.type(), next.step());
        context.failure();
    }

    @Override
    public void close() {
    }
}
