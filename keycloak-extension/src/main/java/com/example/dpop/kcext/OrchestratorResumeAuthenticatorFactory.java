package com.example.dpop.kcext;

import org.keycloak.Config;
import org.keycloak.authentication.Authenticator;
import org.keycloak.authentication.AuthenticationFlowCallbackFactory;
import org.keycloak.models.AuthenticationExecutionModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.ProviderConfigProperty;

import java.util.List;

/**
 * {@link AuthenticationFlowCallbackFactory} (not just a plain {@code AuthenticatorFactory}) so
 * {@link OrchestratorResumeAuthenticator#onTopFlowSuccess} fires at the true end of the top-level
 * flow - the RestoreData end-of-flow hook (docs/05-api.md Abschnitt 3),
 * replacing the separate OrchestratorRestoreDataListener. Only reachable because
 * infra/tofu/keycloak's own config wraps this execution in its own tiny subflow - see
 * OrchestratorResumeAuthenticator's own class doc for why that's required.
 */
public class OrchestratorResumeAuthenticatorFactory implements AuthenticationFlowCallbackFactory {

    public static final String PROVIDER_ID = "orchestrator-resume-authenticator";

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public String getDisplayType() {
        return "Orchestrator Resume";
    }

    @Override
    public String getReferenceCategory() {
        return "orchestrator";
    }

    @Override
    public boolean isConfigurable() {
        return false;
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
        return "First step of every orchestrator-driven browser flow run (docs/05-api.md "
                + "Abschnitt 3) - creates/resumes this run's channel and, on step-up, resubmits RestoreData. Place "
                + "before auth-cookie so it covers the native-password branch too.";
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return List.of();
    }

    @Override
    public Authenticator create(KeycloakSession session) {
        return new OrchestratorResumeAuthenticator(session);
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
