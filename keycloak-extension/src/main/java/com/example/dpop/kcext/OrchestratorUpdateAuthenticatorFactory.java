package com.example.dpop.kcext;

import org.keycloak.Config;
import org.keycloak.authentication.Authenticator;
import org.keycloak.authentication.AuthenticatorFactory;
import org.keycloak.models.AuthenticationExecutionModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.ProviderConfigProperty;

import java.util.List;

public class OrchestratorUpdateAuthenticatorFactory implements AuthenticatorFactory {

    public static final String PROVIDER_ID = "orchestrator-update-authenticator";

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public String getDisplayType() {
        return "Orchestrator Update (native AMR report)";
    }

    @Override
    public String getReferenceCategory() {
        return "orchestrator";
    }

    @Override
    public boolean isConfigurable() {
        return true;
    }

    @Override
    public AuthenticationExecutionModel.Requirement[] getRequirementChoices() {
        return new AuthenticationExecutionModel.Requirement[]{
                AuthenticationExecutionModel.Requirement.REQUIRED,
                AuthenticationExecutionModel.Requirement.DISABLED
        };
    }

    @Override
    public boolean isUserSetupAllowed() {
        return false;
    }

    @Override
    public String getHelpText() {
        return "Invisible step placed right after a native authenticator (e.g. auth-username-password-form) "
                + "to report what it just proved to the orchestrator. "
                + "Never renders a form of its own.";
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        ProviderConfigProperty nativeToolId = new ProviderConfigProperty();
        nativeToolId.setName("nativeToolId");
        nativeToolId.setLabel("Native tool id");
        nativeToolId.setType(ProviderConfigProperty.STRING_TYPE);
        nativeToolId.setHelpText("Stable per-authenticator-TYPE id, resolved server-side via a "
                + "NativeAuthenticatorDescriptor (e.g. 'kc-username-password-form').");

        ProviderConfigProperty amrSourceId = new ProviderConfigProperty();
        amrSourceId.setName("amrSourceId");
        amrSourceId.setLabel("AMR source id (optional)");
        amrSourceId.setType(ProviderConfigProperty.STRING_TYPE);
        amrSourceId.setHelpText("Defaults to this execution's own id if left empty - stable across a "
                + "retry of the same execution.");

        return List.of(nativeToolId, amrSourceId);
    }

    @Override
    public Authenticator create(KeycloakSession session) {
        return new OrchestratorUpdateAuthenticator();
    }

    @Override
    public void init(Config.Scope config) {
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
    }

    @Override
    public void close() {
    }
}
