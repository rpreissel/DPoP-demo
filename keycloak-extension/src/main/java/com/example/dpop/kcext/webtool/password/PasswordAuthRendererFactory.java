package com.example.dpop.kcext.webtool.password;

import com.example.dpop.kcext.webtool.AbstractWebToolRendererFactory;
import com.example.dpop.kcext.webtool.WebToolRenderContext;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.core.Response;
import org.keycloak.forms.login.LoginFormsProvider;

/** Web-channel counterpart to frontend/src/tools/password/index.tsx's {@code authPassword} module. */
public class PasswordAuthRendererFactory extends AbstractWebToolRendererFactory {

    public static final String PROVIDER_ID = "auth-password";

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public String title() {
        return "Passwort";
    }

    @Override
    public String hint() {
        return "Mit dem hinterlegten Passwort";
    }

    @Override
    public Response render(LoginFormsProvider form, WebToolRenderContext ctx) {
        if (!"auth".equals(ctx.step())) return null;
        JsonNode demoPassword = ctx.demo().get("password");
        return form
                .setAttribute("demoPassword", demoPassword != null ? demoPassword.asText() : null)
                .createForm("tool-password-auth.ftl");
    }
}
