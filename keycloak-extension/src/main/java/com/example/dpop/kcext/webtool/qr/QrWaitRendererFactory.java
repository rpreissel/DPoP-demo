package com.example.dpop.kcext.webtool.qr;

import com.example.dpop.kcext.webtool.AbstractWebToolRendererFactory;
import com.example.dpop.kcext.webtool.WebToolRenderContext;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.core.Response;
import org.keycloak.forms.login.LoginFormsProvider;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Shared rendering for `auth-qr`/`auth-qr-lookup` (docs/05-api.md, Peer-Login bestätigen) - both
 * run the same two steps, so only the two toolIds/labels differ:
 * <ul>
 *   <li>{@code waitForApp}: QR code and pairing code, polled until the app decides.</li>
 *   <li>{@code enterCode}: the app approved and shows a confirmation code, which is typed here -
 *       only then is this browser logged in (review 2026-09, M-2).</li>
 * </ul>
 */
abstract class QrWaitRendererFactory extends AbstractWebToolRendererFactory {

    @Override
    public String template() {
        return "tool-qr-wait.ftl";
    }

    @Override
    public Response render(LoginFormsProvider form, WebToolRenderContext ctx) {
        if ("enterCode".equals(ctx.step())) {
            return form.setAttribute("step", "enterCode").createForm(template());
        }
        if (!"waitForApp".equals(ctx.step())) return null;

        JsonNode pairingCodeNode = ctx.stepData().get("pairingCode");
        if (pairingCodeNode == null) return null;
        String pairingCode = pairingCodeNode.asText();

        // /app/ (not /?...) so the link lands directly in the App-Kanal's own app instead of the
        // Willkommen page (docs/10-frontend.md #1); intent=confirm_peer_login is the same wire
        // vocabulary AuthIntent.fromRequest already accepts on POST /app/channels, just carried via
        // the URL instead of a request body (docs/04-orchestrierung.md, CONFIRM_PEER_LOGIN).
        String deepLink = ctx.settings().publicOrchestratorBaseUrl() + "/app/?intent=confirm_peer_login&pairingCode="
                + URLEncoder.encode(pairingCode, StandardCharsets.UTF_8);

        return form
                .setAttribute("step", "waitForApp")
                .setAttribute("pairingCode", pairingCode)
                .setAttribute("deepLink", deepLink)
                .setAttribute("qrDataUri", QrImageEncoder.dataUri(deepLink))
                .createForm(template());
    }
}
