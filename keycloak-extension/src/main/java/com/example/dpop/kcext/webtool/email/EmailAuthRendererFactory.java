package com.example.dpop.kcext.webtool.email;

import com.example.dpop.kcext.KcText;
import com.example.dpop.kcext.webtool.AbstractWebToolRendererFactory;
import com.example.dpop.kcext.webtool.WebToolRenderContext;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.core.Response;
import org.keycloak.forms.login.LoginFormsProvider;

/** Web-channel counterpart to frontend/src/tools/email/index.tsx's {@code authEmail} module. */
public class EmailAuthRendererFactory extends AbstractWebToolRendererFactory {

    public static final String PROVIDER_ID = "auth-email";

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public KcText title() {
        return KcText.t("E-Mail");
    }

    @Override
    public KcText hint() {
        return KcText.t("Code an die bestätigte E-Mail-Adresse");
    }

    @Override
    public String template() {
        return "tool-email-auth.ftl";
    }

    @Override
    public Response render(LoginFormsProvider form, WebToolRenderContext ctx) {
        if (!"auth".equals(ctx.step())) return null;
        JsonNode demoTan = ctx.demo().get("tan");
        return form
                .setAttribute("demoTan", demoTan != null ? demoTan.asText() : null)
                .createForm(template());
    }
}
