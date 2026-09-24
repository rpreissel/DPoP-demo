package com.example.dpop.kcext.webtool.identfsc;

import com.example.dpop.kcext.KcText;
import com.example.dpop.kcext.webtool.AbstractWebToolRendererFactory;
import com.example.dpop.kcext.webtool.WebToolRenderContext;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.core.Response;
import org.keycloak.forms.login.LoginFormsProvider;

import java.util.Set;

/**
 * Web-channel counterpart to ident-fsc, whose single "input" step never changes (docs/06-
 * ablaeufe.md #2) - kvnr/name/vorname/geburtsdatum/fsc all merge into one PATCH. The two pages
 * are this renderer's own choice, the same two the React form makes: the personal-data page
 * (always all four fields) while {@code stepData.missingFields} still names any of them, the code
 * page (always just fsc) once the backend has checked that data and asks for the code. A failed
 * attempt carries no missingFields: the page it was sent from shows again, with the error.
 */
public class IdentFscRendererFactory extends AbstractWebToolRendererFactory {

    public static final String PROVIDER_ID = "ident-fsc";

    /** What the first page collects - everything the backend stages before it asks for fsc. */
    private static final Set<String> PERSONAL_FIELDS = Set.of("kvnr", "name", "vorname", "geburtsdatum");

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public KcText title() {
        return KcText.t("Freischaltcode");
    }

    @Override
    public KcText hint() {
        return KcText.t("Persönliche Daten und Freischaltcode");
    }

    @Override
    public Response render(LoginFormsProvider form, WebToolRenderContext ctx) {
        if (!"input".equals(ctx.step())) return null;
        JsonNode missing = ctx.stepData().get("missingFields");
        boolean personalienPage = false;
        if (missing != null) {
            for (JsonNode field : missing) {
                if (PERSONAL_FIELDS.contains(field.asText())) personalienPage = true;
            }
        } else {
            personalienPage = ctx.submittedFields().stream().anyMatch(PERSONAL_FIELDS::contains);
        }
        return form
                .setAttribute("personalienPage", personalienPage)
                .setAttribute("demoPersonsJson", demoPersonsJson(ctx))
                .createForm("tool-ident-fsc.ftl");
    }
}
