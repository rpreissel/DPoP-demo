// Äquivalent zu infra/tofu/keycloak/main.tf: keycloak_openid_client_scope.orchestrator_claims_scope,
// keycloak_generic_protocol_mapper.orchestrator_acr_amr, keycloak_openid_user_attribute_protocol_mapper.
// orchestrator_account_id. Der Scope wird von OrchestratorAcrAmrMapper (keycloak-extension) über
// einen eigenen Default-Client-Scope statt direkt am Client aufgehängt, damit dasselbe Verhalten
// trivial von jedem späteren Client wiederverwendet werden kann (siehe V4, V5).

step("orchestrator-claims scope anlegen") {
    up {
        clientScopes().create(ClientScopeRepresentation().apply {
            name = "orchestrator-claims"
            protocol = "openid-connect"
        }).close()
    }
    down {
        clientScopes().get(scopeDbId("orchestrator-claims")).remove()
    }
}

// Der Client pickt OrchestratorAcrAmrMapper.PROVIDER_ID über diesen Scope auf statt direkt am
// Client, damit dasselbe Verhalten trivial von jedem späteren Client wiederverwendbar ist.
step("orchestrator-acr-amr mapper anlegen") {
    up {
        clientScopes().get(scopeDbId("orchestrator-claims")).protocolMappers.createMapper(ProtocolMapperRepresentation().apply {
            name = "orchestrator-acr-amr"
            protocol = "openid-connect"
            protocolMapper = "orchestrator-acr-amr-mapper"
            // AbstractOIDCProtocolMapper (keycloak-extension's own base class) überspringt setClaim
            // komplett, außer diese beiden Keys sind wörtlich "true" - keine echte Konfigurationswahl,
            // nur was das Gate der Basisklasse verlangt.
            config = mapOf("access.token.claim" to "true", "id.token.claim" to "true")
        }).close()
    }
    down {
        val mappers = clientScopes().get(scopeDbId("orchestrator-claims")).protocolMappers
        mappers.getMappers().first { it.name == "orchestrator-acr-amr" }.let { mappers.delete(it.id) }
    }
}

// Lässt den Orchestrator accountId direkt aus den Claims eines echten AccessTokens auflösen
// (KeycloakOidcTokenValidator, demo-only Web-Kanal Journey-Log-Lesepfad) statt es über die
// Keycloak-Session-Id zurückzuentwickeln - liest dasselbe orchestratorAccountId-Attribut, das
// account-sync schon schreibt.
step("orchestrator-account-id mapper anlegen") {
    up {
        clientScopes().get(scopeDbId("orchestrator-claims")).protocolMappers.createMapper(ProtocolMapperRepresentation().apply {
            name = "orchestrator-account-id"
            protocol = "openid-connect"
            protocolMapper = "oidc-usermodel-attribute-mapper"
            config = mapOf(
                "user.attribute" to "orchestratorAccountId",
                "claim.name" to "orchestrator_account_id",
                "jsonType.label" to "String",
                "id.token.claim" to "true",
                "access.token.claim" to "true",
                "userinfo.token.claim" to "true",
            )
        }).close()
    }
    down {
        val mappers = clientScopes().get(scopeDbId("orchestrator-claims")).protocolMappers
        mappers.getMappers().first { it.name == "orchestrator-account-id" }.let { mappers.delete(it.id) }
    }
}
