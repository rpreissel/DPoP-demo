package com.example.dpop.kcext.webtool.identfsc;

import com.example.dpop.kcext.webtool.AbstractWebToolRendererFactory;
import com.example.dpop.kcext.webtool.WebToolRenderContext;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.core.Response;
import org.keycloak.forms.login.LoginFormsProvider;

import java.util.HashSet;
import java.util.Set;

/**
 * Web-channel counterpart to ident-fsc, whose single "input" step never changes (docs/06-
 * ablaeufe.md #2) - kvnr/name/vorname/fsc all merge into one PATCH, and {@code stepData.
 * missingFields} alone says which of them are still needed. Unlike the sms/email/password
 * renderers, which switch on {@code ctx.step()}, this one switches on the missingFields set
 * itself: the first round asks for kvnr/name/vorname together, the second (once those three are
 * known) asks only for fsc.
 */
public class IdentFscRendererFactory extends AbstractWebToolRendererFactory {

    public static final String PROVIDER_ID = "ident-fsc";

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public String title() {
        return "Freischaltcode";
    }

    @Override
    public String hint() {
        return "Identifizierung per Freischaltcode";
    }

    @Override
    public Response render(LoginFormsProvider form, WebToolRenderContext ctx) {
        if (!"input".equals(ctx.step())) return null;
        JsonNode missing = ctx.stepData().get("missingFields");
        Set<String> missingFields = new HashSet<>();
        if (missing != null) {
            missing.forEach(f -> missingFields.add(f.asText()));
        }
        return form
                .setAttribute("needsFsc", missingFields.contains("fsc"))
                .setAttribute("needsKvnr", missingFields.contains("kvnr"))
                .setAttribute("needsName", missingFields.contains("name"))
                .setAttribute("needsVorname", missingFields.contains("vorname"))
                .createForm("tool-ident-fsc.ftl");
    }
}
