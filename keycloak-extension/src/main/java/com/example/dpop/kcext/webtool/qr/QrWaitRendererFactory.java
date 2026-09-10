package com.example.dpop.kcext.webtool.qr;

import com.example.dpop.kcext.OrchestratorConfig;
import com.example.dpop.kcext.webtool.AbstractWebToolRendererFactory;
import com.example.dpop.kcext.webtool.WebToolRenderContext;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.core.Response;
import org.keycloak.forms.login.LoginFormsProvider;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Shared rendering for `auth-qr`/`auth-qr-lookup` (docs/ideen/qr-login-ueber-app.md #5/#6/#7) -
 * both wait on the exact same `waitForApp` step with the exact same `stepData` shape
 * ({@code pairingCode}, {@code verificationCode}), so only the two toolIds/labels differ.
 */
abstract class QrWaitRendererFactory extends AbstractWebToolRendererFactory {

    @Override
    public Response render(LoginFormsProvider form, WebToolRenderContext ctx) {
        if (!"waitForApp".equals(ctx.step())) return null;

        JsonNode pairingCodeNode = ctx.stepData().get("pairingCode");
        JsonNode verificationCodeNode = ctx.stepData().get("verificationCode");
        if (pairingCodeNode == null) return null;
        String pairingCode = pairingCodeNode.asText();
        String verificationCode = verificationCodeNode != null ? verificationCodeNode.asText() : null;

        String deepLink = OrchestratorConfig.DEMO_APP_BASE_URL + "/?pairingCode="
                + URLEncoder.encode(pairingCode, StandardCharsets.UTF_8);

        return form
                .setAttribute("pairingCode", pairingCode)
                .setAttribute("verificationCode", verificationCode)
                .setAttribute("deepLink", deepLink)
                .setAttribute("qrDataUri", QrImageEncoder.dataUri(deepLink))
                .createForm("tool-qr-wait.ftl");
    }
}
