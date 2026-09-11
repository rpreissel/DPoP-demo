package com.example.dpop.kcext;

import org.keycloak.Config;
import org.keycloak.authentication.Authenticator;
import org.keycloak.authentication.AuthenticatorFactory;
import org.keycloak.models.AuthenticationExecutionModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.ProviderConfigProperty;

import java.util.List;

public class OrchestratorAuthenticatorFactory implements AuthenticatorFactory {

    public static final String PROVIDER_ID = "orchestrator-authenticator";

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public String getDisplayType() {
        return "Orchestrator Authenticator";
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
        return "Drives the orchestrator's kc-facade (docs/05-api.md Abschnitt 3) - offers "
                + "every orchestrator tool this account can use, or (with 'Static tool id' set) always "
                + "activates one directly instead of showing a selection.";
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        ProviderConfigProperty toolId = new ProviderConfigProperty();
        toolId.setName("toolId");
        toolId.setLabel("Static tool id");
        toolId.setType(ProviderConfigProperty.STRING_TYPE);
        toolId.setHelpText("Only valid for account-independent tools (e.g. ident-fsc). Leave empty for "
                + "step-up executions - their candidates are account-specific (docs/04-orchestrierung.md Abschnitt 3, KC_SELECT_METHOD).");

        ProviderConfigProperty targetAcr = new ProviderConfigProperty();
        targetAcr.setName("targetAcr");
        targetAcr.setLabel("Target ACR");
        targetAcr.setType(ProviderConfigProperty.STRING_TYPE);
        targetAcr.setHelpText("This execution's LoA level, already translated to an orchestrator ACR "
                + "string (e.g. loa2) - set on step-up executions, one per Condition-LoA subflow "
                + "(Keycloak-eigene LoA-Subflow-Konfiguration).");

        ProviderConfigProperty intent = new ProviderConfigProperty();
        intent.setName("intent");
        intent.setLabel("Entry intent");
        intent.setType(ProviderConfigProperty.STRING_TYPE);
        intent.setHelpText("Only meaningful on the channel's very first call (a fresh login/registration "
                + "flow run, never a step-up). Leave empty for kc_select_method (today's login/step-up "
                + "behaviour). Set to 'register' on the registration flow's entry execution to run "
                + "identification + enrollment instead (docs/04-orchestrierung.md #2/#3). Any other value "
                + "is rejected by the orchestrator.");

        return List.of(toolId, targetAcr, intent);
    }

    @Override
    public Authenticator create(KeycloakSession session) {
        return new OrchestratorAuthenticator();
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
