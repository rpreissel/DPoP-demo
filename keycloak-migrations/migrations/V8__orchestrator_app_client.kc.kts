// Dediziertes Gegenstueck zu V5 fuer den APP-Kanal: KcTokenProvider (DPoP-demo-xso.3) ruft den
// Custom-Grant urn:dpop-demo:account-token bislang ueber orchestrator-admin auf - denselben Client,
// den KeycloakAdminClient fuer die Admin-REST-API (User-Sync) mit realm-management manage-users/
// view-realm nutzt. AccountTokenGrantType selbst prueft keine Client-Rolle (siehe dessen eigene
// Doku) - der Grant braucht also keinerlei Admin-Rechte, bekommt sie nur zufaellig mit, weil er
// bislang denselben Client-Secret wie die Admin-REST-Aufrufe verwendet. Dieser Client trennt beide
// Verantwortlichkeiten: orchestrator-app-token darf NUR Access-/Refresh-Tokens fuer App-Kanal-
// Accounts minten, nie die Admin-REST-API aufrufen.

val secret = System.getenv("ORCHESTRATOR_APP_CLIENT_SECRET") ?: "change-me-orchestrator-app-token"

step("orchestrator-app-token client anlegen") {
    up {
        clients().create(ClientRepresentation().apply {
            clientId = "orchestrator-app-token"
            name = "orchestrator-app-token"
            setPublicClient(false)
            setStandardFlowEnabled(false)
            setDirectAccessGrantsEnabled(false)
            // Kein Service Account: der Custom-Grant mintet ein Token fuer den ANGEGEBENEN
            // account_id, nicht fuer den Client selbst - anders als orchestrator-admin (V5), das
            // seinen eigenen Service Account fuer die Admin-REST-API braucht.
            setServiceAccountsEnabled(false)
        }).close()
        setClientSecret("orchestrator-app-token", secret)
    }
    down {
        clients().get(clientDbId("orchestrator-app-token")).remove()
    }
}

// Derselbe "orchestrator-claims"-Scope wie orchestrator-admin (V5) und der Browser-Client (V4) -
// ohne ihn laeuft OrchestratorAcrAmrMapper nie fuer Tokens, die als dieser Client gemintet werden.
step("orchestrator-app-token default scopes setzen") {
    up {
        val client = clients().get(clientDbId("orchestrator-app-token"))
        listOf("profile", "email", "roles", "orchestrator-claims").forEach {
            client.addDefaultClientScope(scopeDbId(it))
        }
    }
    down {
        val client = clients().get(clientDbId("orchestrator-app-token"))
        listOf("profile", "email", "roles", "orchestrator-claims").forEach {
            client.removeDefaultClientScope(scopeDbId(it))
        }
    }
}
