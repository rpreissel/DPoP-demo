package com.example.dpop.kcext.webtool;

import jakarta.ws.rs.core.Response;
import org.keycloak.forms.login.LoginFormsProvider;
import org.keycloak.provider.Provider;

/**
 * One tool's own web-channel rendering (docs/ideen/web-keycloak-kanal.md, DPoP-demo-3yd.6) - the
 * kc-facade's counterpart to the App channel's {@code ToolModule.render()}
 * (frontend/src/tools/types.ts). Registered as a normal Keycloak provider, looked up by toolId via
 * {@link WebToolRendererFactory#getId()} - {@code OrchestratorAuthenticator} falls back to the
 * generic {@code orchestrator-tool.ftl} scaffold whenever no factory is registered for a toolId, or
 * this method returns null for the current step, so adding a tool's own rendering never requires
 * touching the dispatcher.
 */
public interface WebToolRenderer extends Provider {

    /** Null if this tool has no bespoke UI for {@code ctx.step()} - caller falls back to the generic form. */
    Response render(LoginFormsProvider form, WebToolRenderContext ctx);

    @Override
    default void close() {
    }
}
