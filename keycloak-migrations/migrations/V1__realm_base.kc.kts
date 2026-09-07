// Äquivalent zu infra/tofu/keycloak/main.tf: keycloak_realm.realm (die reinen Einstellungen -
// Anlegen/Löschen des Realms selbst übernimmt MigrationRunner, siehe dessen Doku),
// keycloak_realm_user_profile, keycloak_realm_events.

step("realm einstellungen setzen") {
    up {
        updateRealm {
            displayName = "DPoP-Demo"
            // orchestrator-select.ftl/orchestrator-tool.ftl (keycloak-extension) leben unter
            // diesem Theme - ohne diese Zuordnung faellt Keycloak auf das Default-Theme zurueck.
            loginTheme = "orchestrator"
            // auth-username-password-form (LoA1) akzeptiert damit die E-Mail-Adresse als
            // Alternative zum username.
            setLoginWithEmailAllowed(true)
        }
    }
    down {
        updateRealm {
            displayName = null
            loginTheme = null
            setLoginWithEmailAllowed(false)
        }
    }
}

// Keycloaks deklaratives User Profile (seit 24.x Default) verwirft jedes User-Attribut, das hier
// nicht deklariert ist - ohne das würde orchestratorAccountId (von KeycloakAdminClient auf jedem
// synchronisierten User gesetzt) von der API akzeptiert, aber nie persistiert. Diese Ressource ist
// für das GESAMTE Profil zuständig, nicht additiv.
step("user profile konfigurieren") {
    up {
        users().userProfile().update(upConfig("orchestratorAccountId" to setOf("admin")))
    }
    down {
        users().userProfile().update(upConfig())
    }
}

step("events aktivieren") {
    up {
        updateRealmEventsConfig(getRealmEventsConfig().apply {
            setEventsEnabled(true)
            eventsListeners = listOf("jboss-logging")
        })
    }
    down {
        updateRealmEventsConfig(getRealmEventsConfig().apply {
            setEventsEnabled(false)
            eventsListeners = listOf("jboss-logging")
        })
    }
}
