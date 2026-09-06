package com.example.dpop.kcext.credential;

import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.services.resources.admin.ext.AdminRealmResourceProvider;
import org.keycloak.services.resources.admin.ext.AdminRealmResourceProviderFactory;

/**
 * Mounts {@link AccountPublicKeyResource} at `/admin/realms/{realm}/orchestrator-keys` (id below =
 * URL segment, `RealmAdminResource`'s `{extension}` dispatch) - reuses Keycloak's own Admin REST
 * API authentication/authorization instead of a bespoke bearer-token check, since the caller is
 * already the same `keycloak-sync.admin-client-id` service account every other admin call uses.
 */
public class AccountPublicKeyResourceProviderFactory implements AdminRealmResourceProviderFactory {

    public static final String ID = "orchestrator-keys";

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public AdminRealmResourceProvider create(KeycloakSession session) {
        return new AccountPublicKeyResourceProvider();
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
