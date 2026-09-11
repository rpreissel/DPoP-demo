// Zweiter, demo/test-only Client, gebunden an den V10-Flow (orchestrator-browser-qr-test) statt
// an den produktionsnahen "orchestrator-browser" (V4) - siehe V10s Kommentar für den Grund. Sonst
// identisch zu V4: PUBLIC + PKCE, dieselben Redirect-URIs/Web-Origins, dieselben Default-Scopes -
// nur der gebundene Flow unterscheidet sich.

val qrTestClientId = System.getenv("KEYCLOAK_BROWSER_QR_TEST_CLIENT_ID") ?: "dpop-demo-web-qr-test"

step("browser-qr-test client anlegen") {
    up {
        val flowId = flows().getFlows().first { it.alias == "orchestrator-browser-qr-test" }.id
        clients().create(ClientRepresentation().apply {
            clientId = qrTestClientId
            name = "$qrTestClientId (Demo: QR-Login auf LoA-1 testen)"
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
        clients().get(clientDbId(qrTestClientId)).remove()
    }
}

step("browser-qr-test default scopes setzen") {
    up {
        val client = clients().get(clientDbId(qrTestClientId))
        listOf("profile", "email", "roles", "web-origins", "orchestrator-claims").forEach {
            client.addDefaultClientScope(scopeDbId(it))
        }
    }
    down {
        val client = clients().get(clientDbId(qrTestClientId))
        listOf("profile", "email", "roles", "web-origins", "orchestrator-claims").forEach {
            client.removeDefaultClientScope(scopeDbId(it))
        }
    }
}
