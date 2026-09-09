package com.example.dpop.kcext.webtool.identeid;

import com.example.dpop.kcext.webtool.AbstractWebToolRendererFactory;
import com.example.dpop.kcext.webtool.WebToolRenderContext;
import jakarta.ws.rs.core.Response;
import org.keycloak.forms.login.LoginFormsProvider;

import java.util.Set;

/**
 * Web-channel counterpart to the three-step ident-eid tool (docs/06-ablaeufe.md #6): "input"
 * (kvnr/name/vorname, resolves the person), "card" (the simulated eID card's own Ausweisdaten),
 * "pin" (the eID PIN) - each its own {@code nextStep}, unlike ident-fsc's single reused step.
 */
public class IdentEidRendererFactory extends AbstractWebToolRendererFactory {

    public static final String PROVIDER_ID = "ident-eid";
    private static final Set<String> SUPPORTED_STEPS = Set.of("input", "card", "pin");

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public String title() {
        return "eID";
    }

    @Override
    public String hint() {
        return "Identifizierung per Online-Ausweisfunktion";
    }

    @Override
    public Response render(LoginFormsProvider form, WebToolRenderContext ctx) {
        if (!SUPPORTED_STEPS.contains(ctx.step())) return null;
        return form
                .setAttribute("step", ctx.step())
                .createForm("tool-ident-eid.ftl");
    }
}
