// Äquivalent zu infra/tofu/keycloak/main.tf: keycloak_openid_client.browser,
// keycloak_openid_client_default_scopes.browser_default_scopes. Braucht den Flow aus V2
// (authenticationFlowBindingOverrides) und den Scope aus V3 (default scopes) - muss deshalb nach
// beiden laufen.
//
// Die Browser-facing RP-Client (echter Web-Kanal) ist bewusst getrennt von orchestrator-admin
// (V5): dieser hier läuft NIE mit Service-Account, nie mit Admin-Rechten - orchestrator-admin ist
// das genaue Gegenteil. PUBLIC + PKCE (S256), nicht CONFIDENTIAL: die Web-Kanal-Demo-UI tauscht den
// Code direkt aus dem Browser gegen Tokens (kein Backend-Proxy) - ein Client-Secret müsste sonst
// im Browser-Bundle mitausgeliefert werden.

val browserClientId = System.getenv("KEYCLOAK_BROWSER_CLIENT_ID") ?: "dpop-demo-web"

step("browser client anlegen") {
    up {
        val flowId = flows().getFlows().first { it.alias == "orchestrator-browser" }.id
        clients().create(ClientRepresentation().apply {
            clientId = browserClientId
            name = browserClientId
            setPublicClient(true)
            setStandardFlowEnabled(true)
            setDirectAccessGrantsEnabled(false)
            setServiceAccountsEnabled(false)
            redirectUris = listOf("http://localhost:8080/*", "http://localhost:5173/*")
            webOrigins = listOf("http://localhost:8080", "http://localhost:5173")
            attributes = mapOf("pkce.code.challenge.method" to "S256")
            authenticationFlowBindingOverrides = mapOf("browser" to flowId)
        }).close()
    }
    down {
        clients().get(clientDbId(browserClientId)).remove()
    }
}

step("browser default scopes setzen") {
    up {
        val client = clients().get(clientDbId(browserClientId))
        listOf("profile", "email", "roles", "web-origins", "orchestrator-claims").forEach {
            client.addDefaultClientScope(scopeDbId(it))
        }
    }
    down {
        val client = clients().get(clientDbId(browserClientId))
        listOf("profile", "email", "roles", "web-origins", "orchestrator-claims").forEach {
            client.removeDefaultClientScope(scopeDbId(it))
        }
    }
}
