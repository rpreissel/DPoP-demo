// ===================== V4__account_token_grant_client =====================

// Nur der Orchestrator darf den Grant urn:dpop-demo:account-token aufrufen (Review 2026-09-26, F-1).
// AccountTokenGrantType akzeptiert ausschliesslich vertrauliche Clients mit diesem Attribut; ohne es
// koennte jeder Client des Realms - auch der oeffentliche Browser-Client - den Grant aufrufen, und die
// Konto-Assertion waere das einzige Tor.

step("account-token-grant nur fuer orchestrator-app-token") {
    up {
        val client = clients().get(clientDbId(setup.appTokenClientId))
        client.update(client.toRepresentation().apply {
            attributes = attributes.orEmpty() + ("dpop-demo.account-token-grant" to "true")
        })
    }
    down {
        val client = clients().get(clientDbId(setup.appTokenClientId))
        client.update(client.toRepresentation().apply {
            attributes = attributes.orEmpty() - "dpop-demo.account-token-grant"
        })
    }
}
