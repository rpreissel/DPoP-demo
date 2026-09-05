package com.example.dpop.kcext.webtool.password;

import com.example.dpop.kcext.webtool.AbstractWebToolRendererFactory;
import com.example.dpop.kcext.webtool.WebToolRenderContext;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.core.Response;
import org.keycloak.forms.login.LoginFormsProvider;

/** Web-channel counterpart to frontend/src/tools/password/index.tsx's {@code enrollPasswordTool} module. */
public class PasswordEnrollRendererFactory extends AbstractWebToolRendererFactory {

    public static final String PROVIDER_ID = "enroll-password";

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
        return "Eigenes Passwort festlegen";
    }

    @Override
    public Response render(LoginFormsProvider form, WebToolRenderContext ctx) {
        if (!"enroll".equals(ctx.step())) return null;
        JsonNode demoPassword = ctx.demo().get("password");
        return form
                .setAttribute("demoPassword", demoPassword != null ? demoPassword.asText() : null)
                .createForm("tool-password-enroll.ftl");
    }
}
