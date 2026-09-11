package com.example.dpop.kcext;

import com.example.dpop.kcext.webtool.WebToolRenderContext;
import com.example.dpop.kcext.webtool.WebToolRenderer;
import com.example.dpop.kcext.webtool.WebToolRendererFactory;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.core.Response;
import org.keycloak.forms.login.LoginFormsProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.sessions.AuthenticationSessionModel;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The form-building half of {@link OrchestratorAuthenticator}'s rendering, extracted so
 * {@link OrchestratorManageMethodsRequiredAction} can reuse it too (docs/ideen/
 * manage-auth-methods-im-web-kanal.md) - only what's genuinely identical between the two: which
 * FreeMarker template to pick and which attributes to set on an already-obtained
 * {@link LoginFormsProvider}. Deliberately takes {@code session}/{@code form}/{@code authSession}
 * directly rather than a shared context abstraction - {@code AuthenticationFlowContext} and
 * {@code RequiredActionContext} have no common supertype and diverge enough elsewhere
 * ({@code setUser}, {@code failure()}'s signature) that unifying them would cost more than the
 * few lines it'd save; each caller's own dispatch loop stays separate, only this part is shared.
 */
final class WebFormRenderer {

    private WebFormRenderer() {
    }

    /**
     * {@code response} is null only on a retry path with no fresh {@code ChannelResponse} to read
     * from - falls back to a generic heading there rather than failing.
     */
    static Response selectForm(KeycloakSession session, LoginFormsProvider form, AuthenticationSessionModel authSession,
            List<String> options, OrchestratorClient.ChannelResponse response, String error) {
        Map<String, String> optionLabels = new LinkedHashMap<>();
        for (String option : options) {
            WebToolRendererFactory factory = rendererFactoryFor(session, option);
            if (factory != null) optionLabels.put(option, factory.title());
        }
        // The backend already names this specific selection screen (JourneyState.selectionTitle/
        // -Description, docs/04-orchestrierung.md #4) - "Identifikation erforderlich",
        // "Anmeldeverfahren einrichten", "Passwort einrichten" are all real, DIFFERENT screens that
        // must not collapse into one generic "Anmeldemethode wählen" heading.
        String title = response != null && response.stepData().get("title") != null
                ? response.stepData().get("title").asText() : "Anmeldemethode wählen";
        JsonNode descriptionNode = response != null ? response.stepData().get("description") : null;
        var built = form
                .setAuthenticationSession(authSession)
                .setAttribute("title", title)
                .setAttribute("description", descriptionNode != null ? descriptionNode.asText() : null)
                .setAttribute("options", options)
                .setAttribute("optionLabels", optionLabels);
        if (error != null) built.setError(error);
        return built.createForm("orchestrator-select.ftl");
    }

    static Response toolForm(KeycloakSession session, LoginFormsProvider form, AuthenticationSessionModel authSession,
            OrchestratorClient.Next next, OrchestratorClient.ChannelResponse response, String error) {
        String stepError = response.stepDataError();
        String effectiveError = error != null ? error : stepError;

        WebToolRenderer renderer = session.getProvider(WebToolRenderer.class, next.toolId());
        if (renderer != null) {
            WebToolRendererFactory factory = rendererFactoryFor(session, next.toolId());
            var built = form
                    .setAuthenticationSession(authSession)
                    .setAttribute("toolId", next.toolId())
                    .setAttribute("title", factory != null ? factory.title() : next.toolId())
                    .setAttribute("hint", factory != null ? factory.hint() : "");
            if (effectiveError != null) built.setError(effectiveError);
            WebToolRenderContext ctx = new WebToolRenderContext(
                    next.toolId(), next.step(), response.stepData(), response.demo(), effectiveError
            );
            Response rendered = renderer.render(built, ctx);
            if (rendered != null) return rendered;
        }

        // Generic fallback for every toolId without its own WebToolRenderer: one text input per
        // "missingFields" entry - the submitted body must use exactly those field names.
        Map<String, String> fields = new LinkedHashMap<>();
        var missingFields = response.stepData().get("missingFields");
        if (missingFields != null && missingFields.isArray()) {
            missingFields.forEach(fieldName -> fields.put(fieldName.asText(), ""));
        }
        var built = form
                .setAuthenticationSession(authSession)
                .setAttribute("toolId", next.toolId())
                .setAttribute("fields", fields);
        if (effectiveError != null) built.setError(effectiveError);
        return built.createForm("orchestrator-tool.ftl");
    }

    static Response errorForm(LoginFormsProvider form, AuthenticationSessionModel authSession, String message) {
        return form.setAuthenticationSession(authSession).setError(message).createForm("orchestrator-error.ftl");
    }

    /**
     * The "manage" landing screen: active methods (each with its own "Entfernen" submit) plus
     * "Neues Verfahren hinzufügen"/"Fertig". {@code methods} is converted to plain
     * {@code Map<String,String>} rows - FreeMarker's default object wrapper exposes Java Bean
     * getters (get&lt;Prop&gt;), not the {@code id()}/{@code method()}/{@code label()} accessor
     * names a record actually generates, so handing it a raw {@code MethodView} would silently
     * render blank fields instead of failing loudly.
     */
    static Response methodsListForm(LoginFormsProvider form, AuthenticationSessionModel authSession,
            List<OrchestratorClient.MethodView> methods, String notice) {
        List<Map<String, String>> rows = new java.util.ArrayList<>();
        for (OrchestratorClient.MethodView m : methods) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("id", m.id());
            row.put("method", m.method());
            row.put("label", m.label());
            rows.add(row);
        }
        var built = form
                .setAuthenticationSession(authSession)
                .setAttribute("methods", rows);
        if (notice != null) built.setInfo(notice);
        return built.createForm("orchestrator-manage-methods.ftl");
    }

    private static WebToolRendererFactory rendererFactoryFor(KeycloakSession session, String toolId) {
        return (WebToolRendererFactory) session.getKeycloakSessionFactory()
                .getProviderFactory(WebToolRenderer.class, toolId);
    }
}
