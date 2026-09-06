package com.example.dpop.kcext.grant;

import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.protocol.oidc.grants.OAuth2GrantType;
import org.keycloak.protocol.oidc.grants.OAuth2GrantTypeFactory;

/** Registers {@link AccountTokenGrantType} as Keycloak's pluggable oauth2-grant-type SPI (DPoP-demo-xso.3). */
public class AccountTokenGrantTypeFactory implements OAuth2GrantTypeFactory {

    @Override
    public String getId() {
        return AccountTokenGrantType.GRANT_TYPE;
    }

    // Exactly 2 chars - DefaultTokenContextEncoderProvider packs sessionType+tokenType+grantType
    // shortcuts into a fixed 6-char token-id prefix, 2 chars each; anything else breaks every
    // access token this grant issues with "Incorrect token id: ... Expected length of 6."
    @Override
    public String getShortcut() {
        return "at";
    }

    @Override
    public OAuth2GrantType create(KeycloakSession session) {
        return new AccountTokenGrantType();
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
