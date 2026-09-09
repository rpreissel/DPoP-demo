package com.example.dpop.kcext.webtool.sms;

import com.example.dpop.kcext.webtool.AbstractWebToolRendererFactory;
import com.example.dpop.kcext.webtool.WebToolRenderContext;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.core.Response;
import org.keycloak.forms.login.LoginFormsProvider;

/** Web-channel counterpart to the auth-sms tool. */
public class SmsAuthRendererFactory extends AbstractWebToolRendererFactory {

    public static final String PROVIDER_ID = "auth-sms";

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public String title() {
        return "SMS";
    }

    @Override
    public String hint() {
        return "Code an die hinterlegte Telefonnummer";
    }

    @Override
    public Response render(LoginFormsProvider form, WebToolRenderContext ctx) {
        if (!"auth".equals(ctx.step())) return null;
        JsonNode demoTan = ctx.demo().get("tan");
        return form
                .setAttribute("demoTan", demoTan != null ? demoTan.asText() : null)
                .createForm("tool-sms-auth.ftl");
    }
}
