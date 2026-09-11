package com.example.dpop.kcext.webtool.qr;

import com.example.dpop.kcext.webtool.AbstractWebToolRendererFactory;
import com.example.dpop.kcext.webtool.WebToolRenderContext;
import jakarta.ws.rs.core.Response;
import org.keycloak.forms.login.LoginFormsProvider;

/**
 * Web-channel counterpart to `frontend/src/tools/qr/EnrollQrForm.tsx` - a pure opt-in, no
 * credential to enter (docs/ideen/qr-login-ueber-app.md #2/#5), so its own explicit page rather
 * than the generic `orchestrator-tool.ftl` scaffold (docs/ideen/
 * manage-auth-methods-im-web-kanal.md #5): a single confirm button, no fields, mirroring the
 * App channel's wording. Registering this factory at all is also what makes `enroll-qr` appear
 * in {@code WebToolAvailability.renderableToolIds()} in the first place - without it, the
 * orchestrator would never offer it as a Web-channel candidate.
 */
public class EnrollQrRendererFactory extends AbstractWebToolRendererFactory {

    public static final String PROVIDER_ID = "enroll-qr";

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public String title() {
        return "QR-Login";
    }

    @Override
    public String hint() {
        return "Web-Login per App bestätigen erlauben";
    }

    @Override
    public Response render(LoginFormsProvider form, WebToolRenderContext ctx) {
        if (!"enroll".equals(ctx.step())) return null;
        return form.createForm("tool-qr-enroll.ftl");
    }
}
