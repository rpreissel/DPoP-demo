package com.example.dpop.kcext.webtool.identkvnr;

import com.example.dpop.kcext.webtool.AbstractWebToolRendererFactory;
import com.example.dpop.kcext.webtool.WebToolRenderContext;
import jakarta.ws.rs.core.Response;
import org.keycloak.forms.login.LoginFormsProvider;

/**
 * Web-channel counterpart to ident-kvnr: the correlation step that assigns an already attested
 * identity to its register person (docs/12-entscheidungen.md ADR-18). One step, one field - it
 * only ever runs after an attestation, never as a standalone identification.
 */
public class IdentKvnrRendererFactory extends AbstractWebToolRendererFactory {

    public static final String PROVIDER_ID = "ident-kvnr";

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public String title() {
        return "Versichertennummer";
    }

    @Override
    public String hint() {
        return "Konto der Registerperson zuordnen";
    }

    @Override
    public Response render(LoginFormsProvider form, WebToolRenderContext ctx) {
        if (!"input".equals(ctx.step())) return null;
        return form
                .setAttribute("demoPersonsJson", demoPersonsJson(ctx))
                .createForm("tool-ident-kvnr.ftl");
    }
}
