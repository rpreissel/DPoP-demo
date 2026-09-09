package com.example.dpop.kcext.webtool.email;

import com.example.dpop.kcext.webtool.AbstractWebToolRendererFactory;
import com.example.dpop.kcext.webtool.WebToolRenderContext;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.core.Response;
import org.keycloak.forms.login.LoginFormsProvider;

import java.util.Set;

/**
 * Web-channel counterpart to frontend/src/tools/email/index.tsx's {@code authEmailLookup} module -
 * "auth" asks for the email address, "codeInput" asks for the confirmation code.
 */
public class EmailLookupRendererFactory extends AbstractWebToolRendererFactory {

    public static final String PROVIDER_ID = "auth-email-lookup";
    private static final Set<String> SUPPORTED_STEPS = Set.of("auth", "codeInput");

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public String title() {
        return "E-Mail";
    }

    @Override
    public String hint() {
        return "E-Mail-Adresse + Bestätigungscode";
    }

    @Override
    public Response render(LoginFormsProvider form, WebToolRenderContext ctx) {
        if (!SUPPORTED_STEPS.contains(ctx.step())) return null;
        JsonNode demoEmail = ctx.demo().get("email");
        JsonNode demoTan = ctx.demo().get("tan");
        return form
                .setAttribute("step", ctx.step())
                .setAttribute("demoEmail", demoEmail != null ? demoEmail.asText() : null)
                .setAttribute("demoTan", demoTan != null ? demoTan.asText() : null)
                .setAttribute("demoPersonsJson", demoPersonsJson(ctx))
                .createForm("tool-email-lookup.ftl");
    }
}
