package com.example.dpop.kcext;

import org.keycloak.models.ClientSessionContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.ProtocolMapperModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.protocol.oidc.mappers.AbstractOIDCProtocolMapper;
import org.keycloak.protocol.oidc.mappers.OIDCAccessTokenMapper;
import org.keycloak.protocol.oidc.mappers.OIDCAttributeMapperHelper;
import org.keycloak.protocol.oidc.mappers.OIDCIDTokenMapper;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.representations.IDToken;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The standard way Keycloak gets a plugin's own values into a minted token (docs/ideen/
 * web-keycloak-kanal.md #10): at token-mint time the auth-notes {@link OrchestratorAuthenticator}
 * wrote as user-session-notes across the flow have already materialized on the UserSessionModel -
 * this mapper just copies them into the acr/amr claims. Deliberately overwrites Keycloak's own acr
 * mapper output rather than merging with it: on a WEB channel the orchestrator is the sole,
 * combining ACR/AMR instance for the whole flow run (docs/ideen/web-keycloak-kanal.md #8), so its
 * value alone belongs in the token, not a union with Keycloak's native LoA tracking.
 */
public class OrchestratorAcrAmrMapper extends AbstractOIDCProtocolMapper implements OIDCAccessTokenMapper, OIDCIDTokenMapper {

    public static final String PROVIDER_ID = "orchestrator-acr-amr-mapper";

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public String getDisplayType() {
        return "Orchestrator ACR/AMR";
    }

    @Override
    public String getDisplayCategory() {
        return TOKEN_MAPPER_CATEGORY;
    }

    @Override
    public String getHelpText() {
        return "Writes the orchestrator's combined acr/amr (docs/ideen/web-keycloak-kanal.md #8) into "
                + "the token, read from the UserSessionModel notes OrchestratorAuthenticator wrote.";
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        // AbstractOIDCProtocolMapper.transformAccessToken/transformIDToken skip calling setClaim
        // entirely unless the mapper's own config carries these two keys as "true" - not something
        // this mapper leaves configurable (it always belongs in both), but the config keys still
        // have to exist for the base class's own gate.
        List<ProviderConfigProperty> configProperties = new ArrayList<>();
        OIDCAttributeMapperHelper.addIncludeInTokensConfig(configProperties, OrchestratorAcrAmrMapper.class);
        return configProperties;
    }

    @Override
    protected void setClaim(IDToken token, ProtocolMapperModel mappingModel, UserSessionModel userSession,
                             KeycloakSession keycloakSession, ClientSessionContext clientSessionCtx) {
        String acr = userSession.getNote(OrchestratorNotes.USER_SESSION_NOTE_ACR);
        if (acr != null) {
            token.setAcr(acr);
        }
        String amr = userSession.getNote(OrchestratorNotes.USER_SESSION_NOTE_AMR);
        if (amr != null && !amr.isBlank()) {
            List<String> methods = Arrays.asList(amr.split(","));
            token.getOtherClaims().put("amr", methods);
        }
    }
}
