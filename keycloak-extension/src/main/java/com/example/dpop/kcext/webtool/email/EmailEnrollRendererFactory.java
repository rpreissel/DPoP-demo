package com.example.dpop.kcext.webtool.email;

import com.example.dpop.kcext.KcText;
import com.example.dpop.kcext.webtool.AbstractWebToolRendererFactory;
import com.example.dpop.kcext.webtool.WebToolRenderContext;
import jakarta.ws.rs.core.Response;
import org.keycloak.forms.login.LoginFormsProvider;

/**
 * Web-channel counterpart to frontend/src/tools/email/index.tsx's {@code enrollEmailTool}: turning
 * the already confirmed address into a sign-in method completes on activation, so there is no page
 * to render. The factory still exists - it is what declares the tool available in this channel and
 * what names it on the selection page, where the orchestrator always offers it rather than
 * starting it on its own (ToolDescriptor.completesOnActivation).
 */
public class EmailEnrollRendererFactory extends AbstractWebToolRendererFactory {

    public static final String PROVIDER_ID = "enroll-email";

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public KcText title() {
        return KcText.t("E-Mail");
    }

    @Override
    public KcText hint() {
        return KcText.t("Ihre bestätigte E-Mail-Adresse, ohne Code");
    }

    /** No page of its own. */
    @Override
    public String template() {
        return null;
    }

    @Override
    public Response render(LoginFormsProvider form, WebToolRenderContext ctx) {
        return null;
    }
}
