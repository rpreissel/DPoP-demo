package com.example.dpop.kcext.webtool.identeid;

import com.example.dpop.kcext.KcText;
import com.example.dpop.kcext.webtool.AbstractWebToolRendererFactory;
import com.example.dpop.kcext.webtool.WebToolRenderContext;
import jakarta.ws.rs.core.Response;
import org.keycloak.forms.login.LoginFormsProvider;

import java.util.Set;

/**
 * Web-channel counterpart to the two-step ident-eid tool (docs/06-ablaeufe.md #6): "card" (the
 * simulated eID card's own Ausweisdaten, nothing typed beforehand) and "pin" (the eID PIN), each
 * its own {@code nextStep}, unlike ident-fsc's single reused step. Assigning the attested
 * identity to a register person is ident-kvnr's separate step (ADR-18).
 */
public class IdentEidRendererFactory extends AbstractWebToolRendererFactory {

    public static final String PROVIDER_ID = "ident-eid";
    private static final Set<String> SUPPORTED_STEPS = Set.of("card", "pin");

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public KcText title() {
        return KcText.t("eID");
    }

    @Override
    public KcText hint() {
        return KcText.t("Online-Ausweisfunktion (simuliert)");
    }

    @Override
    public String template() {
        return "tool-ident-eid.ftl";
    }

    @Override
    public Response render(LoginFormsProvider form, WebToolRenderContext ctx) {
        if (!SUPPORTED_STEPS.contains(ctx.step())) return null;
        return form
                .setAttribute("step", ctx.step())
                .setAttribute("demoPersonsJson", demoPersonsJson(ctx))
                .createForm(template());
    }
}
