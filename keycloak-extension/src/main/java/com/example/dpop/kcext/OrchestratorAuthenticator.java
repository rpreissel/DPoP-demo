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

import java.util.List;
import java.util.Set;

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

    private final OrchestratorClient client;

    OrchestratorAuthenticator(OrchestratorClient client) {
        this.client = client;
    }

    @Override
    public void authenticate(AuthenticationFlowContext context) {
        try {
            // Read up front: it also decides WHICH channel this run talks to (channelSessionIdFor).
            String intent = context.getAuthenticatorConfig() == null ? null
                    : context.getAuthenticatorConfig().getConfig().get("intent");
            String channelSessionId = OrchestratorNotes.channelSessionIdFor(context.getAuthenticationSession(), intent);
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
            // server-side (KcChannelService.entryIntentFor is only consulted when isFreshChannel) -
            // which is why a changed intent gets a fresh channel above.

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
            context.challenge(errorForm(context, KcTexts.of(context.getSession(), "Anmeldung derzeit nicht möglich.")));
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
                    // "Abbrechen" ends the LOGIN, not just the orchestrator journey: abandoning the
                    // journey alone made the orchestrator restart the very same entry intent, so
                    // the user landed on this page again (and a registration restarted forever).
                    // cancelLogin() hands control back to the client - OIDC redirects to its
                    // redirect_uri with error=access_denied. The kc channel is left to its TTL.
                    context.cancelLogin();
                    return;
                } else {
                    String selectedToolId = form.getFirst("toolId");
                    if (selectedToolId == null || selectedToolId.isBlank()) {
                        context.challenge(errorForm(context, KcTexts.of(context.getSession(), "Bitte eine Methode auswählen.")));
                        return;
                    }
                    response = client.activateTool(channelSessionId, selectedToolId);
                }
            } else if ("confirm".equals(pendingKind)) {
                String answer = form.getFirst("orchestrator_answer");
                if (!"accept".equals(answer) && !"decline".equals(answer)) {
                    context.challenge(errorForm(context, KcTexts.of(context.getSession(), "Bitte eine Antwort auswählen.")));
                    return;
                }
                response = client.answer(channelSessionId, answer);
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
            context.failureChallenge(AuthenticationFlowError.INVALID_CREDENTIALS, currentChallenge(context, e.message(context.getSession())));
        } catch (Exception e) {
            LOG.error("OrchestratorAuthenticator.action failed", e);
            context.failure(AuthenticationFlowError.INTERNAL_ERROR);
        }
    }

    private void handleResponse(AuthenticationFlowContext context, OrchestratorClient.ChannelResponse response, MultivaluedMap<String, String> lastForm) {
        AuthenticationSessionModel authSession = context.getAuthenticationSession();

        if (response.authDataAccountId() != null && context.getUser() == null) {
            UserModel user = AccountUsers.findByAccountId(context.getSession(), context.getRealm(), String.valueOf(response.authDataAccountId()));
            if (user == null) {
                // The orchestrator just named this account - not finding it is an inconsistency,
                // never a reason to invent a user (review 2026-09, P-3: Keycloak creates no users).
                LOG.errorf("Orchestrator named account %d, but the federation does not know it", response.authDataAccountId());
                context.failure(AuthenticationFlowError.INTERNAL_ERROR);
                return;
            }
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
                    context.challenge(errorForm(context, KcTexts.of(context.getSession(), "Das konfigurierte Anmeldeverfahren ist derzeit nicht verfügbar.")));
                    return;
                } catch (Exception e) {
                    LOG.error("Static tool pre-selection '" + staticToolId + "' failed", e);
                    context.failure(AuthenticationFlowError.INTERNAL_ERROR);
                    return;
                }
            }
            if (options.isEmpty()) {
                context.challenge(errorForm(context,
                        KcTexts.of(context.getSession(), "Kein Anmeldeverfahren verfügbar. Prüfen Sie die Tool-ID der LoA-Execution.")));
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
                    context.challenge(errorForm(context, KcTexts.of(context.getSession(), "Anmeldung derzeit nicht möglich.")));
                } catch (Exception e) {
                    LOG.error("Auto-activation of '" + tool.next().toolId() + "' failed", e);
                    context.failure(AuthenticationFlowError.INTERNAL_ERROR);
                }
                return;
            }
            authSession.setAuthNote(OrchestratorNotes.PENDING_KIND, "tool");
            authSession.setAuthNote(OrchestratorNotes.PENDING_TOOL_ID, tool.next().toolId());
            authSession.setAuthNote(OrchestratorNotes.PENDING_TOOL_SESSION_ID, tool.next().toolSessionId());
            context.challenge(WebFormRenderer.toolForm(context.getSession(), context.form(), authSession, tool.next(), response, null,
                    lastForm == null ? Set.of() : lastForm.keySet()));
            return;
        }

        if (outcome instanceof OrchestratorNextDispatch.Confirm confirm) {
            authSession.setAuthNote(OrchestratorNotes.PENDING_KIND, "confirm");
            context.challenge(WebFormRenderer.confirmForm(context.getSession(), context.form(), authSession, confirm.prompt(), null));
            return;
        }

        OrchestratorNextDispatch.Unhandled unhandled = (OrchestratorNextDispatch.Unhandled) outcome;
        LOG.warnf("Unhandled orchestrator next: type=%s step=%s", unhandled.next().type(), unhandled.next().step());
        context.failure(AuthenticationFlowError.INTERNAL_ERROR);
    }

    /**
     * {@code response} is null only on the retry path ({@link #currentChallenge}, which has no
     * fresh {@code ChannelResponse} to read from) - falls back to a generic heading there rather
     * than failing, since a retry error still has to render something. Form-building itself lives
     * in {@link WebFormRenderer}, shared with {@link OrchestratorManageMethodsRequiredAction}.
     */
    private Response selectForm(AuthenticationFlowContext context, List<String> options, OrchestratorClient.ChannelResponse response, String error) {
        return WebFormRenderer.selectForm(context.getSession(), context.form(), context.getAuthenticationSession(), options, response, error,
                offersRegistration(context));
    }

    /**
     * Keycloak's own "Registrieren" link (the realm's registration flow runs the orchestrator's
     * REGISTER journey) belongs on this page only where the native login form would show it too:
     * nobody known yet (not a step-up), registration allowed, and not already inside the
     * registration flow itself.
     */
    private static boolean offersRegistration(AuthenticationFlowContext context) {
        String intent = context.getAuthenticatorConfig() == null ? null
                : context.getAuthenticatorConfig().getConfig().get("intent");
        return context.getUser() == null
                && context.getRealm().isRegistrationAllowed()
                && !"register".equalsIgnoreCase(intent);
    }

    private Response toolForm(AuthenticationFlowContext context, OrchestratorClient.Next next, OrchestratorClient.ChannelResponse response, String error) {
        return WebFormRenderer.toolForm(context.getSession(), context.form(), context.getAuthenticationSession(), next, response, error);
    }

    private Response errorForm(AuthenticationFlowContext context, String message) {
        return WebFormRenderer.errorForm(context.getSession(), context.form(), context.getAuthenticationSession(), message);
    }

    private Response currentChallenge(AuthenticationFlowContext context, String error) {
        AuthenticationSessionModel authSession = context.getAuthenticationSession();
        String pendingKind = authSession.getAuthNote(OrchestratorNotes.PENDING_KIND);
        if ("select".equals(pendingKind)) {
            // Re-render with whatever options this authenticator last knew - a fresh authenticate()
            // pass on retry re-fetches the real, current list; here we only need something to show.
            return errorForm(context, error != null ? error : "Die Auswahl der Anmeldemethode ist fehlgeschlagen.");
        }
        // Through WebFormRenderer like every other page: the template needs t (and Keycloakify texts).
        return errorForm(context, error);
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
