package com.example.dpop.kcext;

import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.authentication.Authenticator;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserProvider;
import org.keycloak.sessions.AuthenticationSessionModel;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The kc-facade's Keycloak-side driver (docs/ideen/web-keycloak-kanal.md #6/#7/#8) - a normal
 * Authentication SPI Authenticator, configurable per Authentication Execution just like Keycloak's
 * own built-ins. Same execution class handles the initial-login case (Section 2's "WEB, initialer
 * Login" anchor) and the step-up case ("WEB, Step-up") - which one applies is read off whether an
 * SSO UserSessionModel already exists when this execution runs, exactly the distinction Section 2
 * itself draws.
 *
 * Renders whatever the orchestrator names as {@code next}: a method-selection page, or a generic
 * form for the fields a tool's {@code stepData} asks for. Config properties:
 * <ul>
 *   <li>{@code toolId} - static pre-selection for an account-independent tool (identification), see
 *       Section 7 "Statische Vorauswahl". Left empty for step-up executions.</li>
 *   <li>{@code targetAcr} - this execution's LoA level, already translated into an orchestrator ACR
 *       string (Section 9's mapping table lives in the tofu config that sets this property, one value
 *       per Condition-LoA subflow's execution).</li>
 * </ul>
 */
public class OrchestratorAuthenticator implements Authenticator {

    private static final Logger LOG = Logger.getLogger(OrchestratorAuthenticator.class);
    // The resolved kcSessionId anchor value itself - re-deriving it live on a LATER request (the
    // browser's form POST back into action()) doesn't work: nothing about it carries over to the
    // next HTTP request in the same flow on its own. Kept as an auth-session note under
    // OrchestratorNotes.ANCHOR_VALUE so every authenticator that can establish this flow run's
    // anchor agrees on the same note key.

    private final OrchestratorClient client = new OrchestratorClient(
            OrchestratorConfig.BASE_URL, OrchestratorConfig.PEER_AUTH_ISSUER, OrchestratorConfig.PEER_AUTH_AUDIENCE
    );

    @Override
    public void authenticate(AuthenticationFlowContext context) {
        try {
            // Whichever ran first in this flow - OrchestratorResumeAuthenticator (step-up: a valid
            // SSO session was found) or OrchestratorUpdateAuthenticator (LoA-1's native password) -
            // already established the anchor by the time this executes: this Condition-LoA-2
            // execution is never the flow's first touch on any reachable path (Resume always runs
            // before it, and LoA-1 always runs before it too whenever LoA-1's own condition isn't
            // skipped by an already-sufficient step-up). Just read it back, never re-derive it.
            String kcSessionId = anchor(context);

            String channelSessionId = OrchestratorNotes.channelSessionId(context);
            // Surfaces on the eventual LOGIN event's details - OrchestratorRestoreDataListener reads
            // it back there to know which channel to fetch RestoreData for (docs/ideen/
            // web-keycloak-kanal.md #6); a plain Authenticator has no other end-of-flow hook.
            context.getEvent().detail(OrchestratorNotes.CHANNEL_SESSION_ID, channelSessionId);
            // Deliberately NOT gated on `stepUp` (= a UserSessionModel already exists): a step-up
            // request against an existing SSO session that auth-cookie found insufficient for the
            // requested level still resolves context.getUser() (the cookie authenticator attaches
            // it before ever checking the achieved level) - well before any UserSessionModel for
            // THIS flow run exists. Using `stepUp` here instead would misreport an already-known
            // account as anonymous and, worse, apply targetAcr's floor-raise to a candidate list
            // resolved as if for lookup-login.
            Long accountId = OrchestratorNotes.accountId(context.getUser());
            // Prefer Keycloak's own requested level (see OrchestratorNotes.requestedAcr) over this
            // execution's static config - falls back to it only when Keycloak has no requested
            // level to report at all (e.g. a plain login with no acr_values).
            String staticTargetAcr = context.getAuthenticatorConfig() == null ? null
                    : context.getAuthenticatorConfig().getConfig().get("targetAcr");
            String targetAcr = OrchestratorNotes.requestedAcr(context);
            if (targetAcr == null) targetAcr = staticTargetAcr;

            // Deliberately NOT OrchestratorNotes.nativeAmr(context), and no restoreData here:
            // OrchestratorResumeAuthenticator is this flow run's one dedicated place for both -
            // reporting a native proof happens via OrchestratorUpdateAuthenticator's OWN
            // upsertChannel call the moment that proof happens, and RestoreData is resubmitted
            // exclusively by Resume, once, right when it first resolves the SSO session. Resending
            // either again from this authenticator (which never itself proves anything native, and
            // runs after Resume already had its one chance) only re-triggers a no-op evidence merge.
            OrchestratorClient.ChannelResponse response = client.upsertChannel(
                    channelSessionId, kcSessionId, accountId, targetAcr, List.of(), null
            );
            handleResponse(context, response, null);
        } catch (OrchestratorClient.OrchestratorApiException e) {
            LOG.warnf("Orchestrator upsertChannel failed: %s", e.getMessage());
            context.challenge(errorForm(context, "Anmeldung derzeit nicht möglich."));
        } catch (Exception e) {
            LOG.error("OrchestratorAuthenticator.authenticate failed", e);
            context.failure(AuthenticationFlowError.INTERNAL_ERROR);
        }
    }

    @Override
    public void action(AuthenticationFlowContext context) {
        try {
            AuthenticationSessionModel authSession = context.getAuthenticationSession();
            String kcSessionId = anchor(context);
            String channelSessionId = OrchestratorNotes.channelSessionId(context);

            MultivaluedMap<String, String> form = context.getHttpRequest().getDecodedFormParameters();
            String pendingKind = authSession.getAuthNote(OrchestratorNotes.PENDING_KIND);

            OrchestratorClient.ChannelResponse response;
            if ("select".equals(pendingKind)) {
                String selectedToolId = form.getFirst("toolId");
                if (selectedToolId == null || selectedToolId.isBlank()) {
                    context.challenge(errorForm(context, "Bitte eine Methode auswählen."));
                    return;
                }
                response = client.activateTool(kcSessionId, channelSessionId, selectedToolId);
            } else {
                String toolId = authSession.getAuthNote(OrchestratorNotes.PENDING_TOOL_ID);
                String toolSessionId = authSession.getAuthNote(OrchestratorNotes.PENDING_TOOL_SESSION_ID);
                if (toolId == null || toolSessionId == null) {
                    context.failure(AuthenticationFlowError.INTERNAL_ERROR);
                    return;
                }
                if ("true".equals(form.getFirst("orchestrator_abandon"))) {
                    response = client.abandonTool(kcSessionId, toolSessionId, toolId);
                } else {
                    Map<String, String> fields = new LinkedHashMap<>();
                    form.forEach((key, values) -> {
                        if (!key.startsWith("orchestrator_") && !values.isEmpty()) fields.put(key, values.get(0));
                    });
                    response = client.patchTool(kcSessionId, toolSessionId, toolId, fields);
                }
            }
            handleResponse(context, response, form);
        } catch (OrchestratorClient.OrchestratorApiException e) {
            LOG.infof("Orchestrator tool call failed: %s", e.getMessage());
            context.failureChallenge(AuthenticationFlowError.INVALID_CREDENTIALS, currentChallenge(context, e.message));
        } catch (Exception e) {
            LOG.error("OrchestratorAuthenticator.action failed", e);
            context.failure(AuthenticationFlowError.INTERNAL_ERROR);
        }
    }

    private void handleResponse(AuthenticationFlowContext context, OrchestratorClient.ChannelResponse response, MultivaluedMap<String, String> lastForm) {
        AuthenticationSessionModel authSession = context.getAuthenticationSession();

        if (response.authDataAccountId() != null && context.getUser() == null) {
            UserModel user = findOrCreateUser(context, response.authDataAccountId());
            context.setUser(user);
            authSession.setAuthenticatedUser(user);
        }
        if (response.authDataAcr() != null) {
            authSession.setUserSessionNote(OrchestratorNotes.USER_SESSION_NOTE_ACR, response.authDataAcr());
        }
        if (!response.authDataAmr().isEmpty()) {
            authSession.setUserSessionNote(OrchestratorNotes.USER_SESSION_NOTE_AMR, String.join(",", response.authDataAmr().keySet()));
        }

        OrchestratorClient.Next next = response.next();
        if (next == null || next.isAuthenticated() || "AUTHENTICATED".equals(response.channelState())) {
            context.success();
            return;
        }

        if (next.isSelectMethod()) {
            String staticToolId = context.getAuthenticatorConfig() == null ? null
                    : context.getAuthenticatorConfig().getConfig().get("toolId");
            List<String> options = response.stepDataOptions();
            if (staticToolId != null && !staticToolId.isBlank() && options.contains(staticToolId)) {
                try {
                    OrchestratorClient.ChannelResponse activated = client.activateTool(
                            anchor(context), OrchestratorNotes.channelSessionId(context), staticToolId
                    );
                    handleResponse(context, activated, lastForm);
                    return;
                } catch (Exception e) {
                    LOG.warnf(e, "Static tool pre-selection '%s' failed, falling back to selectMethod form", staticToolId);
                }
            }
            authSession.setAuthNote(OrchestratorNotes.PENDING_KIND, "select");
            context.challenge(selectForm(context, options, null));
            return;
        }

        if (next.isTool()) {
            if (next.toolSessionId() == null) {
                // A single-candidate auto-activation (JourneyService.nextFor: activatable.size == 1)
                // only ever DESCRIBES which tool comes next - unlike the explicit "select" path
                // below, it never minted an actual ToolSession server-side. Activate it now, exactly
                // like the static toolId pre-selection above does, so PENDING_TOOL_SESSION_ID below
                // is never null.
                try {
                    OrchestratorClient.ChannelResponse activated = client.activateTool(
                            anchor(context), OrchestratorNotes.channelSessionId(context), next.toolId()
                    );
                    handleResponse(context, activated, lastForm);
                } catch (OrchestratorClient.OrchestratorApiException e) {
                    LOG.warnf("Auto-activation of '%s' failed: %s", next.toolId(), e.getMessage());
                    context.challenge(errorForm(context, "Anmeldung derzeit nicht möglich."));
                } catch (Exception e) {
                    LOG.error("Auto-activation of '" + next.toolId() + "' failed", e);
                    context.failure(AuthenticationFlowError.INTERNAL_ERROR);
                }
                return;
            }
            authSession.setAuthNote(OrchestratorNotes.PENDING_KIND, "tool");
            authSession.setAuthNote(OrchestratorNotes.PENDING_TOOL_ID, next.toolId());
            authSession.setAuthNote(OrchestratorNotes.PENDING_TOOL_SESSION_ID, next.toolSessionId());
            context.challenge(toolForm(context, next, response, null));
            return;
        }

        LOG.warnf("Unhandled orchestrator next: type=%s step=%s", next.type(), next.step());
        context.failure(AuthenticationFlowError.INTERNAL_ERROR);
    }

    private String anchor(AuthenticationFlowContext context) {
        return OrchestratorNotes.readAnchor(context);
    }

    private UserModel findOrCreateUser(AuthenticationFlowContext context, long accountId) {
        KeycloakSession session = context.getSession();
        RealmModel realm = context.getRealm();
        UserProvider users = session.users();
        UserModel existing = users.searchForUserByUserAttributeStream(realm, OrchestratorNotes.USER_ATTR_ACCOUNT_ID, String.valueOf(accountId))
                .findFirst().orElse(null);
        if (existing != null) return existing;
        // First time this account authenticates through Keycloak - same pattern as
        // RegistrationUserCreation: mint a Keycloak user record and attach the orchestrator's own
        // identity via a durable attribute, so every later step-up can look it back up by it.
        UserModel created = users.addUser(realm, "orchestrator-account-" + accountId);
        created.setEnabled(true);
        created.setSingleAttribute(OrchestratorNotes.USER_ATTR_ACCOUNT_ID, String.valueOf(accountId));
        return created;
    }

    private Response selectForm(AuthenticationFlowContext context, List<String> options, String error) {
        var form = context.form()
                .setAuthenticationSession(context.getAuthenticationSession())
                .setAttribute("options", options);
        if (error != null) form.setError(error);
        return form.createForm("orchestrator-select.ftl");
    }

    private Response toolForm(AuthenticationFlowContext context, OrchestratorClient.Next next, OrchestratorClient.ChannelResponse response, String error) {
        // A tool's stepData names the fields it still needs under "missingFields" (e.g.
        // tool_spi/AuthPasswordLookupFlow.kt) - the submitted body must use exactly those field
        // names, not "missingFields" itself, which is just the manifest of what to render.
        Map<String, String> fields = new LinkedHashMap<>();
        var missingFields = response.stepData().get("missingFields");
        if (missingFields != null && missingFields.isArray()) {
            missingFields.forEach(fieldName -> fields.put(fieldName.asText(), ""));
        }
        var form = context.form()
                .setAuthenticationSession(context.getAuthenticationSession())
                .setAttribute("toolId", next.toolId())
                .setAttribute("fields", fields);
        String stepError = response.stepDataError();
        if (error != null) form.setError(error);
        else if (stepError != null) form.setError(stepError);
        return form.createForm("orchestrator-tool.ftl");
    }

    private Response errorForm(AuthenticationFlowContext context, String message) {
        return context.form().setAuthenticationSession(context.getAuthenticationSession()).setError(message).createForm("orchestrator-error.ftl");
    }

    private Response currentChallenge(AuthenticationFlowContext context, String error) {
        AuthenticationSessionModel authSession = context.getAuthenticationSession();
        String pendingKind = authSession.getAuthNote(OrchestratorNotes.PENDING_KIND);
        if ("select".equals(pendingKind)) {
            // Re-render with whatever options this authenticator last knew - a fresh authenticate()
            // pass on retry re-fetches the real, current list; here we only need something to show.
            return selectForm(context, List.of(), error);
        }
        return context.form().setAuthenticationSession(authSession).setError(error).createForm("orchestrator-error.ftl");
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
