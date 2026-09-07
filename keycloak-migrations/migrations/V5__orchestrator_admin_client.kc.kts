// Äquivalent zu infra/tofu/keycloak/main.tf: keycloak_openid_client.orchestrator_admin,
// keycloak_openid_client_service_account_role.orchestrator_admin_manage_users/view_realm,
// keycloak_openid_client_default_scopes.orchestrator_admin_default_scopes. Braucht den Scope aus
// V3, läuft aber unabhängig von V2/V4.
//
// Keine handdeklarierten Demo-User mehr: KeycloakAdminClient/KeycloakAccountSyncListener
// (Orchestrator, `keycloak`-Profil) spiegeln jede AccountService create/change/delete in einen
// Keycloak-User über die Admin-REST-API - authentifiziert als dieser Client-eigene Service Account,
// das übliche Muster für ein Backend, das User ohne menschliche Admin-Session verwaltet.

val secret = System.getenv("ORCHESTRATOR_ADMIN_CLIENT_SECRET") ?: "change-me-orchestrator-admin"

fun StepContext.serviceAccountUserId(): String = clients().get(clientDbId("orchestrator-admin")).serviceAccountUser.id

step("orchestrator-admin client anlegen") {
    up {
        clients().create(ClientRepresentation().apply {
            clientId = "orchestrator-admin"
            name = "orchestrator-admin"
            setPublicClient(false)
            setStandardFlowEnabled(false)
            setDirectAccessGrantsEnabled(false)
            setServiceAccountsEnabled(true)
        }).close()
        setClientSecret("orchestrator-admin", secret)
    }
    down {
        clients().get(clientDbId("orchestrator-admin")).remove()
    }
}

step("service-account manage-users Rolle zuweisen") {
    up {
        val realmMgmtId = clientDbId("realm-management")
        val role = clients().get(realmMgmtId).roles().get("manage-users").toRepresentation()
        users().get(serviceAccountUserId()).roles().clientLevel(realmMgmtId).add(listOf(role))
    }
    down {
        val realmMgmtId = clientDbId("realm-management")
        val role = clients().get(realmMgmtId).roles().get("manage-users").toRepresentation()
        users().get(serviceAccountUserId()).roles().clientLevel(realmMgmtId).remove(listOf(role))
    }
}

// Für KeycloakAdminClient.passwordStorageComponentId() (DPoP-demo-25q) - GET .../components ist
// durch die realmweite "view realm"-Berechtigung geschützt, nicht durch manage-users.
step("service-account view-realm Rolle zuweisen") {
    up {
        val realmMgmtId = clientDbId("realm-management")
        val role = clients().get(realmMgmtId).roles().get("view-realm").toRepresentation()
        users().get(serviceAccountUserId()).roles().clientLevel(realmMgmtId).add(listOf(role))
    }
    down {
        val realmMgmtId = clientDbId("realm-management")
        val role = clients().get(realmMgmtId).roles().get("view-realm").toRepresentation()
        users().get(serviceAccountUserId()).roles().clientLevel(realmMgmtId).remove(listOf(role))
    }
}

// Derselbe "orchestrator-claims"-Scope, den der Browser-Client schon bekommt (V4) - ohne ihn läuft
// OrchestratorAcrAmrMapper nie für die eigenen Tokens des Custom-Account-Token-Grants
// (DPoP-demo-xso), da der als DIESER Client authentifiziert, nicht als der Browser-Client.
step("orchestrator-admin default scopes setzen") {
    up {
        val client = clients().get(clientDbId("orchestrator-admin"))
        listOf("profile", "email", "roles", "orchestrator-claims").forEach {
            client.addDefaultClientScope(scopeDbId(it))
        }
    }
    down {
        val client = clients().get(clientDbId("orchestrator-admin"))
        listOf("profile", "email", "roles", "orchestrator-claims").forEach {
            client.removeDefaultClientScope(scopeDbId(it))
        }
    }
}
