package com.example.dpop.kcext;

import com.example.dpop.kcext.webtool.WebToolAvailability;
import com.fasterxml.jackson.databind.JsonNode;
import com.example.dpop.kcext.webtool.WebToolRenderContext;
import com.example.dpop.kcext.webtool.WebToolRenderer;
import com.example.dpop.kcext.webtool.WebToolRendererFactory;
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
                String selectedToolId = form.getFirst("toolId");
                if (selectedToolId == null || selectedToolId.isBlank()) {
                    context.challenge(errorForm(context, "Bitte eine Methode auswählen."));
                    return;
                }
                response = client.activateTool(channelSessionId, selectedToolId);
            } else {
                String toolId = authSession.getAuthNote(OrchestratorNotes.PENDING_TOOL_ID);
                String toolSessionId = authSession.getAuthNote(OrchestratorNotes.PENDING_TOOL_SESSION_ID);
                if (toolId == null || toolSessionId == null) {
                    context.failure(AuthenticationFlowError.INTERNAL_ERROR);
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

        if (next.isTool()) {
            if (next.toolSessionId() == null) {
                // A single-candidate auto-activation (JourneyService.nextFor: activatable.size == 1)
                // only ever DESCRIBES which tool comes next - unlike the explicit "select" path
                // below, it never minted an actual ToolSession server-side. Activate it now, exactly
                // like the static toolId pre-selection above does, so PENDING_TOOL_SESSION_ID below
                // is never null.
                try {
                    OrchestratorClient.ChannelResponse activated = client.activateTool(
                            OrchestratorNotes.channelSessionId(context), next.toolId()
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
        // Keycloak's own built-in JPA password provider (docs/ideen/web-keycloak-kanal.md,
        // DPoP-demo-25q) - Keycloak dispatches CredentialInputValidator/-Updater for a
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
     * than failing, since a retry error still has to render something.
     */
    private Response selectForm(AuthenticationFlowContext context, List<String> options, OrchestratorClient.ChannelResponse response, String error) {
        Map<String, String> optionLabels = new LinkedHashMap<>();
        for (String option : options) {
            WebToolRendererFactory factory = rendererFactoryFor(context.getSession(), option);
            if (factory != null) optionLabels.put(option, factory.title());
        }
        // The backend already names this specific selection screen (JourneyState.selectionTitle/
        // -Description, docs/04-orchestrierung.md #4) - "Identifikation erforderlich",
        // "Anmeldeverfahren einrichten", "Passwort einrichten" are all real, DIFFERENT screens that
        // must not collapse into one generic "Anmeldemethode wählen" heading, same reasoning as
        // frontend/src/types.ts's own OfferingState.selectionTitle doc.
        String title = response != null && response.stepData().get("title") != null
                ? response.stepData().get("title").asText() : "Anmeldemethode wählen";
        JsonNode descriptionNode = response != null ? response.stepData().get("description") : null;
        var form = context.form()
                .setAuthenticationSession(context.getAuthenticationSession())
                .setAttribute("title", title)
                .setAttribute("description", descriptionNode != null ? descriptionNode.asText() : null)
                .setAttribute("options", options)
                .setAttribute("optionLabels", optionLabels);
        if (error != null) form.setError(error);
        return form.createForm("orchestrator-select.ftl");
    }

    private Response toolForm(AuthenticationFlowContext context, OrchestratorClient.Next next, OrchestratorClient.ChannelResponse response, String error) {
        String stepError = response.stepDataError();
        String effectiveError = error != null ? error : stepError;

        WebToolRenderer renderer = context.getSession().getProvider(WebToolRenderer.class, next.toolId());
        if (renderer != null) {
            WebToolRendererFactory factory = rendererFactoryFor(context.getSession(), next.toolId());
            var form = context.form()
                    .setAuthenticationSession(context.getAuthenticationSession())
                    .setAttribute("toolId", next.toolId())
                    .setAttribute("title", factory != null ? factory.title() : next.toolId())
                    .setAttribute("hint", factory != null ? factory.hint() : "");
            if (effectiveError != null) form.setError(effectiveError);
            WebToolRenderContext ctx = new WebToolRenderContext(
                    next.toolId(), next.step(), response.stepData(), response.demo(), effectiveError
            );
            Response rendered = renderer.render(form, ctx);
            if (rendered != null) return rendered;
        }

        // Generic fallback for every toolId without its own WebToolRenderer: one text input per
        // "missingFields" entry (e.g. tool_spi/AuthPasswordLookupFlow.kt) - the submitted body must
        // use exactly those field names, not "missingFields" itself, which is just the manifest of
        // what to render.
        Map<String, String> fields = new LinkedHashMap<>();
        var missingFields = response.stepData().get("missingFields");
        if (missingFields != null && missingFields.isArray()) {
            missingFields.forEach(fieldName -> fields.put(fieldName.asText(), ""));
        }
        var form = context.form()
                .setAuthenticationSession(context.getAuthenticationSession())
                .setAttribute("toolId", next.toolId())
                .setAttribute("fields", fields);
        if (effectiveError != null) form.setError(effectiveError);
        return form.createForm("orchestrator-tool.ftl");
    }

    private WebToolRendererFactory rendererFactoryFor(KeycloakSession session, String toolId) {
        return (WebToolRendererFactory) session.getKeycloakSessionFactory()
                .getProviderFactory(WebToolRenderer.class, toolId);
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
