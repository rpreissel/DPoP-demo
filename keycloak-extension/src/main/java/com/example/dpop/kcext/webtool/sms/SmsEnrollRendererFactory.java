package com.example.dpop.kcext.webtool.sms;

import com.example.dpop.kcext.webtool.AbstractWebToolRendererFactory;
import com.example.dpop.kcext.webtool.WebToolRenderContext;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.core.Response;
import org.keycloak.forms.login.LoginFormsProvider;

import java.util.Set;

/** Web-channel counterpart to the two-step enroll-sms tool. */
public class SmsEnrollRendererFactory extends AbstractWebToolRendererFactory {

    public static final String PROVIDER_ID = "enroll-sms";
    private static final Set<String> SUPPORTED_STEPS = Set.of("enroll", "tanInput");

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
        return "Code an eine Telefonnummer";
    }

    @Override
    public Response render(LoginFormsProvider form, WebToolRenderContext ctx) {
        if (!SUPPORTED_STEPS.contains(ctx.step())) return null;
        JsonNode demoTan = ctx.demo().get("tan");
        return form
                .setAttribute("step", ctx.step())
                .setAttribute("demoTan", demoTan != null ? demoTan.asText() : null)
                .createForm("tool-sms-enroll.ftl");
    }
}
