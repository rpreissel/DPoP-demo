package com.example.dpop.kcext;

import com.example.dpop.kcext.webtool.WebToolAvailability;
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
import org.keycloak.storage.UserStorageProvider;

import java.util.List;

/**
 * The kc-facade's Keycloak-side driver (docs/05-api.md Abschnitt 3) - a normal
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
 *   <li>{@code intent} - which kc entry intent a fresh channel starts with (docs/04-orchestrierung.md
 *       #2/#3): empty means {@code kc_select_method} (today's login/step-up behaviour), {@code register}
 *       runs identification + enrollment instead. Only read on the channel's very first call.</li>
 * </ul>
 *
 */
public class OrchestratorAuthenticator implements Authenticator {

    private static final Logger LOG = Logger.getLogger(OrchestratorAuthenticator.class);

    private final OrchestratorClient client = new OrchestratorClient(
            OrchestratorConfig.BASE_URL, OrchestratorConfig.PEER_AUTH_ISSUER, OrchestratorConfig.PEER_AUTH_AUDIENCE
    );

    @Override
    public void authenticate(AuthenticationFlowContext context) {
        try {
            String channelSessionId = OrchestratorNotes.channelSessionId(context);
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

            // The kc facade's own, deliberately narrow intent switch (docs/04-orchestrierung.md
            // #2/#3): unconfigured means kc_select_method, today's login/step-up behaviour -
            // admin-configurable per execution, same idiom as the static toolId pre-selection
            // below. Only meaningful on this channel's very first call; a later resume ignores it
            // server-side (KcChannelService.entryIntentFor is only consulted when isFreshChannel).
            String intent = context.getAuthenticatorConfig() == null ? null
                    : context.getAuthenticatorConfig().getConfig().get("intent");

            // Deliberately NOT OrchestratorNotes.nativeAmr(context), and no restoreData here:
            // OrchestratorResumeAuthenticator is this flow run's one dedicated place for both -
            // reporting a native proof happens via OrchestratorUpdateAuthenticator's OWN
            // upsertChannel call the moment that proof happens, and RestoreData is resubmitted
            // exclusively by Resume, once, right when it first resolves the SSO session. Resending
            // either again from this authenticator (which never itself proves anything native, and
            // runs after Resume already had its one chance) only re-triggers a no-op evidence merge.
            OrchestratorClient.ChannelResponse response = client.upsertChannel(
                    channelSessionId, accountId, targetAcr, List.of(), null, null,
                    WebToolAvailability.renderableToolIds(context.getSession()), intent
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
            String channelSessionId = OrchestratorNotes.channelSessionId(context);

            MultivaluedMap<String, String> form = context.getHttpRequest().getDecodedFormParameters();
            String pendingKind = authSession.getAuthNote(OrchestratorNotes.PENDING_KIND);

            OrchestratorClient.ChannelResponse response;
            if ("select".equals(pendingKind)) {
                if ("true".equals(form.getFirst("orchestrator_abandon"))) {
                    response = client.abandonJourney(channelSessionId);
                } else {
                    String selectedToolId = form.getFirst("toolId");
                    if (selectedToolId == null || selectedToolId.isBlank()) {
                        context.challenge(errorForm(context, "Bitte eine Methode auswählen."));
                        return;
                    }
                    response = client.activateTool(channelSessionId, selectedToolId);
                }
            } else {
                String toolId = authSession.getAuthNote(OrchestratorNotes.PENDING_TOOL_ID);
                String toolSessionId = authSession.getAuthNote(OrchestratorNotes.PENDING_TOOL_SESSION_ID);
                if (toolId == null || toolSessionId == null) {
                    context.failure(AuthenticationFlowError.INTERNAL_ERROR);
                    return;
                }
                response = OrchestratorNextDispatch.dispatchToolAction(client, channelSessionId, toolId, toolSessionId, form);
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
        OrchestratorNotes.applyAuthData(authSession, response);

        OrchestratorClient.Next next = response.next();
        if (next == null || next.isAuthenticated() || "AUTHENTICATED".equals(response.channelState())) {
            context.success();
            return;
        }

        OrchestratorNextDispatch.Outcome outcome = OrchestratorNextDispatch.classify(next, response);
        if (outcome instanceof OrchestratorNextDispatch.Select select) {
            String staticToolId = context.getAuthenticatorConfig() == null ? null
                    : context.getAuthenticatorConfig().getConfig().get("toolId");
            List<String> options = select.options();
            LOG.debugf("Orchestrator method selection: configured toolId='%s', options=%s",
                    staticToolId, options);
            if (staticToolId != null && !staticToolId.isBlank()) {
                try {
                    OrchestratorClient.ChannelResponse activated = client.activateTool(
                            OrchestratorNotes.channelSessionId(context), staticToolId
                    );
                    handleResponse(context, activated, lastForm);
                    return;
                } catch (OrchestratorClient.OrchestratorApiException e) {
                    LOG.warnf(e, "Static tool pre-selection '%s' failed", staticToolId);
                    context.challenge(errorForm(context, "Das konfigurierte Anmeldeverfahren ist derzeit nicht verfügbar."));
                    return;
                } catch (Exception e) {
                    LOG.error("Static tool pre-selection '" + staticToolId + "' failed", e);
                    context.failure(AuthenticationFlowError.INTERNAL_ERROR);
                    return;
                }
            }
            if (options.isEmpty()) {
                context.challenge(errorForm(context,
                        "Kein Anmeldeverfahren verfügbar. Prüfen Sie die Tool-ID der LoA-Execution."));
                return;
            }
            authSession.setAuthNote(OrchestratorNotes.PENDING_KIND, "select");
            context.challenge(selectForm(context, options, response, null));
            return;
        }

        if (outcome instanceof OrchestratorNextDispatch.Tool tool) {
            if (tool.autoActivate()) {
                // A single-candidate auto-activation (JourneyService.nextFor: activatable.size == 1)
                // only ever DESCRIBES which tool comes next - unlike the explicit "select" path
                // below, it never minted an actual ToolSession server-side. Activate it now, exactly
                // like the static toolId pre-selection above does, so PENDING_TOOL_SESSION_ID below
                // is never null.
                try {
                    OrchestratorClient.ChannelResponse activated = client.activateTool(
                            OrchestratorNotes.channelSessionId(context), tool.next().toolId()
                    );
                    handleResponse(context, activated, lastForm);
                } catch (OrchestratorClient.OrchestratorApiException e) {
                    LOG.warnf("Auto-activation of '%s' failed: %s", tool.next().toolId(), e.getMessage());
                    context.challenge(errorForm(context, "Anmeldung derzeit nicht möglich."));
                } catch (Exception e) {
                    LOG.error("Auto-activation of '" + tool.next().toolId() + "' failed", e);
                    context.failure(AuthenticationFlowError.INTERNAL_ERROR);
                }
                return;
            }
            authSession.setAuthNote(OrchestratorNotes.PENDING_KIND, "tool");
            authSession.setAuthNote(OrchestratorNotes.PENDING_TOOL_ID, tool.next().toolId());
            authSession.setAuthNote(OrchestratorNotes.PENDING_TOOL_SESSION_ID, tool.next().toolSessionId());
            context.challenge(toolForm(context, tool.next(), response, null));
            return;
        }

        OrchestratorNextDispatch.Unhandled unhandled = (OrchestratorNextDispatch.Unhandled) outcome;
        LOG.warnf("Unhandled orchestrator next: type=%s step=%s", unhandled.next().type(), unhandled.next().step());
        context.failure(AuthenticationFlowError.INTERNAL_ERROR);
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
        // Routes the "password" credential type to OrchestratorPasswordStorageProvider instead of
        // Keycloak's own built-in JPA password provider (DPoP-demo-25q) - Keycloak dispatches CredentialInputValidator/-Updater for a
        // federation-linked user to the linked UserStorageProvider component, so this is enough on
        // its own; no provider-priority configuration needed. The component itself is provisioned
        // once per realm (infra/tofu/keycloak/main.tf's keycloak_custom_user_federation resource),
        // found here by provider id since its component id varies per environment.
        realm.getStorageProviders(UserStorageProvider.class)
                .filter(c -> OrchestratorPasswordStorageProviderFactory.PROVIDER_ID.equals(c.getProviderId()))
                .findFirst()
                .ifPresent(component -> created.setFederationLink(component.getId()));
        return created;
    }

    /**
     * {@code response} is null only on the retry path ({@link #currentChallenge}, which has no
     * fresh {@code ChannelResponse} to read from) - falls back to a generic heading there rather
     * than failing, since a retry error still has to render something. Form-building itself lives
     * in {@link WebFormRenderer}, shared with {@link OrchestratorManageMethodsRequiredAction}.
     */
    private Response selectForm(AuthenticationFlowContext context, List<String> options, OrchestratorClient.ChannelResponse response, String error) {
        return WebFormRenderer.selectForm(context.getSession(), context.form(), context.getAuthenticationSession(), options, response, error);
    }

    private Response toolForm(AuthenticationFlowContext context, OrchestratorClient.Next next, OrchestratorClient.ChannelResponse response, String error) {
        return WebFormRenderer.toolForm(context.getSession(), context.form(), context.getAuthenticationSession(), next, response, error);
    }

    private Response errorForm(AuthenticationFlowContext context, String message) {
        return WebFormRenderer.errorForm(context.form(), context.getAuthenticationSession(), message);
    }

    private Response currentChallenge(AuthenticationFlowContext context, String error) {
        AuthenticationSessionModel authSession = context.getAuthenticationSession();
        String pendingKind = authSession.getAuthNote(OrchestratorNotes.PENDING_KIND);
        if ("select".equals(pendingKind)) {
            // Re-render with whatever options this authenticator last knew - a fresh authenticate()
            // pass on retry re-fetches the real, current list; here we only need something to show.
            return errorForm(context, error != null ? error : "Die Auswahl der Anmeldemethode ist fehlgeschlagen.");
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
