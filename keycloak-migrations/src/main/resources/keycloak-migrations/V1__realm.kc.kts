// Das gesamte Realm in einer Datei - Reihenfolge und Zuschnitt der Schritte sind unveraendert die
// der frueheren Einzeldateien V1..V12, deren Namen die Abschnittsueberschriften unten tragen.
// Eine Datei statt zwoelf, weil der Runner ohnehin nur alles zusammen aufbaut oder alles
// zurueckrollt (siehe MigrationRunner: ein Checksummenkonflikt loescht das Realm komplett).
//
// Nichts Dynamisches steht hier: Realm-Name, Client-Ids, Redirect-URIs und die URLs, die als
// Config-Properties an der orchestrator-Komponente landen, kommen ausnahmslos aus setup
// ([RealmSetup] - die Haelfte von KeycloakSetup, die ins Realm geschrieben wird, ausgewaehlt
// ueber keycloak-setup.variant in application-keycloak.yml). Client-Secrets gibt es keine: beide
// Orchestrator-Clients weisen sich per private_key_jwt aus.


// ===================== V1__realm_base =====================

// Äquivalent zu infra/tofu/keycloak/main.tf: keycloak_realm.realm (die reinen Einstellungen -
// Anlegen/Löschen des Realms selbst übernimmt MigrationRunner, siehe dessen Doku),
// keycloak_realm_user_profile, keycloak_realm_events.

step("realm einstellungen setzen") {
    up {
        updateRealm {
            displayName = setup.realmDisplayName
            // orchestrator-select.ftl/orchestrator-tool.ftl (keycloak-extension) leben unter
            // diesem Theme - ohne diese Zuordnung faellt Keycloak auf das Default-Theme zurueck.
            loginTheme = setup.loginTheme
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


// ===================== V2__browser_authentication_flow =====================

// Äquivalent zu infra/tofu/keycloak/main.tf: der komplette "orchestrator-browser"-Flow-Baum
// (keycloak_authentication_flow/_subflow/_execution/_execution_config). Jeder Schritt hier
// entspricht einer terraform-Ressource (Execution + ihre Config zusammen als ein Schritt); die
// Reihenfolge ist exakt die Abhängigkeitsreihenfolge aus main.tf - Keycloak vergibt die Priority
// innerhalb eines Flow-Levels automatisch nach Anlage-Reihenfolge, deshalb muss hier niemand
// manuell mit priority hantieren, solange die Schritte in dieser Reihenfolge laufen.
//
// Struktur folgt Keycloaks eigener LoA-Subflow-Konfiguration: LoA1 = natives Keycloak-Passwort (sofort an
// den Orchestrator gemeldet), LoA2 = orchestrator-eigenes selectMethod.

step("orchestrator-browser flow anlegen") {
    up {
        flows().createFlow(AuthenticationFlowRepresentation().apply {
            alias = "orchestrator-browser"
            description = "Browser flow with LoA conditions driven by the orchestrator's kc-facade"
            providerId = "basic-flow"
            setTopLevel(true)
            setBuiltIn(false)
        })
    }
    down {
        flows().deleteFlow(topFlowId("orchestrator-browser"))
    }
}

// Nur in eigenem (sonst zwecklosem) Subflow gewrappt, damit Keycloaks AuthenticationFlowCallback
// greift: onTopFlowSuccess feuert nur für eine Execution, deren EIGENER PARENT als SUBFLOW endet -
// nie für eine Execution direkt im Top-Level-Flow.
step("orchestrator-resume-wrapper subflow anlegen") {
    up {
        flows().addExecutionFlow("orchestrator-browser", mapOf(
            "alias" to "orchestrator-resume-wrapper",
            "type" to "basic-flow",
            "description" to "Wraps orchestrator-resume-authenticator so its AuthenticationFlowCallback registers",
        ))
        setRequirement("orchestrator-browser", childExecution("orchestrator-browser") { it.displayName == "orchestrator-resume-wrapper" }, "REQUIRED")
    }
    down {
        flows().removeExecution(childExecution("orchestrator-browser") { it.displayName == "orchestrator-resume-wrapper" })
    }
}

step("orchestrator-resume execution anlegen") {
    up {
        flows().addExecution("orchestrator-resume-wrapper", mapOf("provider" to "orchestrator-resume-authenticator"))
        setRequirement(
            "orchestrator-resume-wrapper",
            childExecution("orchestrator-resume-wrapper") { it.providerId == "orchestrator-resume-authenticator" },
            "REQUIRED",
        )
    }
    down {
        flows().removeExecution(childExecution("orchestrator-resume-wrapper") { it.providerId == "orchestrator-resume-authenticator" })
    }
}

// Keycloak verweigert REQUIRED und ALTERNATIVE auf derselben Flow-Ebene (verschluckt den
// ALTERNATIVE-Zweig statt zu fehlern) - deshalb: ein REQUIRED-Schritt, dann ein selbst wieder
// REQUIRED verschachtelter Subflow, der darin die eigentliche ALTERNATIVE-Gruppe hält.
step("orchestrator-browser-forms subflow anlegen") {
    up {
        flows().addExecutionFlow("orchestrator-browser", mapOf(
            "alias" to "orchestrator-browser-forms",
            "type" to "basic-flow",
            "description" to "Cookie SSO reuse vs. interactive auth, tried as alternatives",
        ))
        setRequirement("orchestrator-browser", childExecution("orchestrator-browser") { it.displayName == "orchestrator-browser-forms" }, "REQUIRED")
    }
    down {
        flows().removeExecution(childExecution("orchestrator-browser") { it.displayName == "orchestrator-browser-forms" })
    }
}

step("browser-cookie execution anlegen") {
    up {
        flows().addExecution("orchestrator-browser-forms", mapOf("provider" to "auth-cookie"))
        setRequirement("orchestrator-browser-forms", childExecution("orchestrator-browser-forms") { it.providerId == "auth-cookie" }, "ALTERNATIVE")
    }
    down {
        flows().removeExecution(childExecution("orchestrator-browser-forms") { it.providerId == "auth-cookie" })
    }
}

step("orchestrator-auth-flow subflow anlegen") {
    up {
        flows().addExecutionFlow("orchestrator-browser-forms", mapOf(
            "alias" to "orchestrator-auth-flow",
            "type" to "basic-flow",
            "description" to "LoA-aware branches, each driven by the OrchestratorAuthenticator",
        ))
        setRequirement(
            "orchestrator-browser-forms",
            childExecution("orchestrator-browser-forms") { it.displayName == "orchestrator-auth-flow" },
            "ALTERNATIVE",
        )
    }
    down {
        flows().removeExecution(childExecution("orchestrator-browser-forms") { it.displayName == "orchestrator-auth-flow" })
    }
}

// ── LoA 1: natives Keycloak-Passwort - accountId lebt im Keycloak-User-Attribut
// OrchestratorNotes.USER_ATTR_ACCOUNT_ID (KeycloakAccountSyncListener hält es synchron), sofort
// nach erfolgreicher Anmeldung an den Orchestrator gemeldet, damit LoA-2-Kandidaten "password"
// schon ausschließen.

step("orchestrator-loa-1 subflow anlegen") {
    up {
        flows().addExecutionFlow("orchestrator-auth-flow", mapOf(
            "alias" to "orchestrator-loa-1",
            "type" to "basic-flow",
            "description" to "LoA-1 branch: native Keycloak username/password",
        ))
        setRequirement("orchestrator-auth-flow", childExecution("orchestrator-auth-flow") { it.displayName == "orchestrator-loa-1" }, "CONDITIONAL")
    }
    down {
        flows().removeExecution(childExecution("orchestrator-auth-flow") { it.displayName == "orchestrator-loa-1" })
    }
}

step("loa-1 condition execution anlegen") {
    up {
        flows().addExecution("orchestrator-loa-1", mapOf("provider" to "conditional-level-of-authentication"))
        val id = childExecution("orchestrator-loa-1") { it.providerId == "conditional-level-of-authentication" }
        setRequirement("orchestrator-loa-1", id, "REQUIRED")
        flows().newExecutionConfig(id, AuthenticatorConfigRepresentation().apply {
            alias = "orchestrator-loa-1-condition"
            config = mapOf("loa-condition-level" to "1", "loa-max-age" to "36000")
        }).close()
    }
    down {
        flows().removeExecution(childExecution("orchestrator-loa-1") { it.providerId == "conditional-level-of-authentication" })
    }
}

step("loa-1 password execution anlegen") {
    up {
        flows().addExecution("orchestrator-loa-1", mapOf("provider" to "auth-username-password-form"))
        setRequirement("orchestrator-loa-1", childExecution("orchestrator-loa-1") { it.providerId == "auth-username-password-form" }, "REQUIRED")
    }
    down {
        flows().removeExecution(childExecution("orchestrator-loa-1") { it.providerId == "auth-username-password-form" })
    }
}

step("loa-1 report-password execution anlegen") {
    up {
        flows().addExecution("orchestrator-loa-1", mapOf("provider" to "orchestrator-update-authenticator"))
        val id = childExecution("orchestrator-loa-1") { it.providerId == "orchestrator-update-authenticator" }
        setRequirement("orchestrator-loa-1", id, "REQUIRED")
        // Muss exakt zu NativeAuthenticatorRegistry.kt's "kc-password-form"-Eintrag passen - dort,
        // nicht hier, werden method/maxAcr/factorTypes für diese id tatsächlich aufgelöst.
        flows().newExecutionConfig(id, AuthenticatorConfigRepresentation().apply {
            alias = "orchestrator-loa-1-report-password"
            config = mapOf("nativeToolId" to "kc-password-form")
        }).close()
    }
    down {
        flows().removeExecution(childExecution("orchestrator-loa-1") { it.providerId == "orchestrator-update-authenticator" })
    }
}

// ── LoA 2: Step-up, nur kontospezifische Kandidaten ──────────────────────────────────────────────

step("orchestrator-loa-2 subflow anlegen") {
    up {
        flows().addExecutionFlow("orchestrator-auth-flow", mapOf(
            "alias" to "orchestrator-loa-2",
            "type" to "basic-flow",
            "description" to "LoA-2 branch: orchestrator step-up (account-specific candidates)",
        ))
        setRequirement("orchestrator-auth-flow", childExecution("orchestrator-auth-flow") { it.displayName == "orchestrator-loa-2" }, "CONDITIONAL")
    }
    down {
        flows().removeExecution(childExecution("orchestrator-auth-flow") { it.displayName == "orchestrator-loa-2" })
    }
}

step("loa-2 condition execution anlegen") {
    up {
        flows().addExecution("orchestrator-loa-2", mapOf("provider" to "conditional-level-of-authentication"))
        val id = childExecution("orchestrator-loa-2") { it.providerId == "conditional-level-of-authentication" }
        setRequirement("orchestrator-loa-2", id, "REQUIRED")
        flows().newExecutionConfig(id, AuthenticatorConfigRepresentation().apply {
            alias = "orchestrator-loa-2-condition"
            config = mapOf("loa-condition-level" to "2", "loa-max-age" to "36000")
        }).close()
    }
    down {
        flows().removeExecution(childExecution("orchestrator-loa-2") { it.providerId == "conditional-level-of-authentication" })
    }
}

step("loa-2 orchestrator execution anlegen") {
    up {
        flows().addExecution("orchestrator-loa-2", mapOf("provider" to "orchestrator-authenticator"))
        val id = childExecution("orchestrator-loa-2") { it.providerId == "orchestrator-authenticator" }
        setRequirement("orchestrator-loa-2", id, "REQUIRED")
        // Reduziert auf das, was eine Execution braucht: ihre EIGENE Level-Nummer als
        // Orchestrator-ACR-Übersetzung, statt einer realmweiten Lookup-Tabelle.
        flows().newExecutionConfig(id, AuthenticatorConfigRepresentation().apply {
            alias = "orchestrator-loa-2-orchestrator"
            config = mapOf("targetAcr" to "loa2")
        }).close()
    }
    down {
        flows().removeExecution(childExecution("orchestrator-loa-2") { it.providerId == "orchestrator-authenticator" })
    }
}


// ===================== V3__orchestrator_claims_scope =====================

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


// ===================== V4__browser_client =====================

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

step("browser client anlegen") {
    up {
        val flowId = flows().getFlows().first { it.alias == "orchestrator-browser" }.id
        clients().create(ClientRepresentation().apply {
            clientId = setup.browserClientId
            name = setup.browserClientId
            setPublicClient(true)
            setStandardFlowEnabled(true)
            setDirectAccessGrantsEnabled(false)
            setServiceAccountsEnabled(false)
            redirectUris = setup.browserRedirectUris
            // "+" heisst bei Keycloak: genau die Origins der redirectUris oben - spart eine
            // zweite Liste, die mit der ersten auseinanderlaufen koennte.
            webOrigins = listOf("+")
            attributes = mapOf("pkce.code.challenge.method" to "S256")
            authenticationFlowBindingOverrides = mapOf("browser" to flowId)
        }).close()
    }
    down {
        clients().get(clientDbId(setup.browserClientId)).remove()
    }
}

step("browser default scopes setzen") {
    up {
        val client = clients().get(clientDbId(setup.browserClientId))
        listOf("profile", "email", "roles", "web-origins", "orchestrator-claims").forEach {
            client.addDefaultClientScope(scopeDbId(it))
        }
    }
    down {
        val client = clients().get(clientDbId(setup.browserClientId))
        listOf("profile", "email", "roles", "web-origins", "orchestrator-claims").forEach {
            client.removeDefaultClientScope(scopeDbId(it))
        }
    }
}


// ===================== V5__orchestrator_admin_client =====================

// Äquivalent zu infra/tofu/keycloak/main.tf: keycloak_openid_client.orchestrator_admin,
// keycloak_openid_client_service_account_role.orchestrator_admin_manage_users/view_realm,
// keycloak_openid_client_default_scopes.orchestrator_admin_default_scopes. Braucht den Scope aus
// V3, läuft aber unabhängig von V2/V4.
//
// Keine handdeklarierten Demo-User mehr: KeycloakAdminClient/KeycloakAccountSyncListener
// (Orchestrator, `keycloak`-Profil) spiegeln jede AccountService create/change/delete in einen
// Keycloak-User über die Admin-REST-API - authentifiziert als dieser Client-eigene Service Account,
// das übliche Muster für ein Backend, das User ohne menschliche Admin-Session verwaltet.

fun StepContext.serviceAccountUserId(): String = clients().get(clientDbId(setup.adminApiClientId)).serviceAccountUser.id

/**
 * Client-Attribute fuer private_key_jwt: Keycloak holt den oeffentlichen Schluessel des
 * Orchestrators bei jedem unbekannten `kid` unter dieser Adresse ab, statt ihn hier als Zertifikat
 * eingemauert zu tragen - derselbe Weg, den der Orchestrator umgekehrt fuer Keycloaks
 * Peer-Auth-Schluessel nimmt.
 */
fun orchestratorClientJwtAttributes(setup: RealmSetup) = mapOf(
    "use.jwks.url" to "true",
    "jwks.url" to "${setup.orchestratorBaseUrl}/orchestrator/api/v1/kc/client-jwks/.well-known/jwks.json",
    // Keycloak-Default ist RS256; der Orchestrator signiert mit EC P-256 wie alles andere hier.
    "token.endpoint.auth.signing.alg" to "ES256",
)

step("orchestrator-admin client anlegen") {
    up {
        clients().create(ClientRepresentation().apply {
            clientId = setup.adminApiClientId
            name = setup.adminApiClientId
            setPublicClient(false)
            setStandardFlowEnabled(false)
            setDirectAccessGrantsEnabled(false)
            setServiceAccountsEnabled(true)
            // private_key_jwt statt Client-Secret (ADR-25): der Orchestrator signiert jeden
            // Token-Request mit seinem eigenen Schluessel, Keycloak holt den oeffentlichen Teil
            // unter jwks.url ab. Es gibt damit kein geteiltes Geheimnis mehr - spiegelbildlich zu
            // der Assertion, mit der sich Keycloak beim Orchestrator ausweist (ADR-7).
            clientAuthenticatorType = "client-jwt"
            attributes = orchestratorClientJwtAttributes(setup)
        }).close()
    }
    down {
        clients().get(clientDbId(setup.adminApiClientId)).remove()
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
        val client = clients().get(clientDbId(setup.adminApiClientId))
        listOf("profile", "email", "roles", "orchestrator-claims").forEach {
            client.addDefaultClientScope(scopeDbId(it))
        }
    }
    down {
        val client = clients().get(clientDbId(setup.adminApiClientId))
        listOf("profile", "email", "roles", "orchestrator-claims").forEach {
            client.removeDefaultClientScope(scopeDbId(it))
        }
    }
}


// ===================== V6__orchestrator_password_federation =====================

// Äquivalent zu infra/tofu/keycloak/main.tf: keycloak_custom_user_federation.orchestrator_password.
// Unabhängig von allen anderen Migrationen, braucht nur das Realm aus V1.
//
// Zwei Aufgaben in einer Komponente:
//
// 1. Sie routet den "password"-Credential-Typ für jeden federation-verlinkten User zu
//    OrchestratorStorageProvider statt zu Keycloaks eingebautem JPA-Passwort-Provider - kein
//    Passwort landet je in Keycloak selbst, gleiches Prinzip wie ein LDAP-Federation-Provider, der
//    an sein eigenes Verzeichnis delegiert. KeycloakAdminClient.createUser sucht diese Komponente
//    über ihre provider_id (die eigene id variiert pro Umgebung/Import) und setzt sie als
//    federationLink.
// 2. Ihre Config-Properties SIND die Konfiguration der ganzen Extension (OrchestratorSettings):
//    Authenticators, Required Action und der QR-Renderer lesen sie von hier, statt wie früher aus
//    Umgebungsvariablen des Keycloak-Containers. Damit steht sie sichtbar im Realm - in der
//    Admin-Console änderbar und Teil des Realm-Exports.

step("orchestrator-komponente anlegen") {
    up {
        components().add(ComponentRepresentation().apply {
            name = "orchestrator"
            providerId = "orchestrator"
            providerType = "org.keycloak.storage.UserStorageProvider"
            config = MultivaluedHashMap<String, String>().apply {
                put("priority", listOf("0"))
                put("enabled", listOf("true"))
                put("orchestratorBaseUrl", listOf(setup.orchestratorBaseUrl))
                put("publicOrchestratorBaseUrl", listOf(setup.publicOrchestratorBaseUrl))
                put("peerAuthIssuer", listOf(setup.peerAuthIssuer))
                put("peerAuthAudience", listOf(setup.peerAuthAudience))
            }
        }).close()
    }
    down {
        val id = components().query().first {
            it.name == "orchestrator" && it.providerType == "org.keycloak.storage.UserStorageProvider"
        }.id
        components().removeComponent(id)
    }
}


// ===================== V7__registration_authentication_flow =====================

// Registrierung ueber den Web-Kanal (docs/04-orchestrierung.md #2/#3, DPoP-demo-urt): komplett
// Keycloak-delegiert - kein natives Registrierungsformular (kein RegistrationUserCreation/
// RegistrationPassword), die einzige Execution ist der OrchestratorAuthenticator mit
// intent=register. Struktur bewusst schlanker als V2s Login-Flow: keine LoA-Subflows noetig, da
// die REGISTER-Journey selbst schon die volle Fallback-/Pflicht-Kette faehrt (Identifying ->
// Enrolling -> ConfirmingEmail -> PasswordObligation), nicht Keycloaks Conditional-LoA-Maschinerie.
//
// Der resume-wrapper (gleiches Muster wie V2s "orchestrator-resume-wrapper") laeuft trotzdem
// zuerst: ein Seitenreload mitten in der Registrierung soll denselben Kanal fortsetzen koennen,
// nicht neu beginnen - dafuer braucht orchestrator-resume-authenticator seinen eigenen Subflow,
// damit Keycloaks AuthenticationFlowCallback fuer ihn ueberhaupt greift.

step("orchestrator-registration flow anlegen") {
    up {
        flows().createFlow(AuthenticationFlowRepresentation().apply {
            alias = "orchestrator-registration"
            description = "Registration driven entirely by the orchestrator's kc-facade (intent=register)"
            providerId = "basic-flow"
            setTopLevel(true)
            setBuiltIn(false)
        })
    }
    down {
        flows().deleteFlow(topFlowId("orchestrator-registration"))
    }
}

step("orchestrator-registration-resume-wrapper subflow anlegen") {
    up {
        flows().addExecutionFlow("orchestrator-registration", mapOf(
            "alias" to "orchestrator-registration-resume-wrapper",
            "type" to "basic-flow",
            "description" to "Wraps orchestrator-resume-authenticator so its AuthenticationFlowCallback registers",
        ))
        setRequirement(
            "orchestrator-registration",
            childExecution("orchestrator-registration") { it.displayName == "orchestrator-registration-resume-wrapper" },
            "REQUIRED",
        )
    }
    down {
        flows().removeExecution(
            childExecution("orchestrator-registration") { it.displayName == "orchestrator-registration-resume-wrapper" }
        )
    }
}

step("orchestrator-registration-resume execution anlegen") {
    up {
        flows().addExecution("orchestrator-registration-resume-wrapper", mapOf("provider" to "orchestrator-resume-authenticator"))
        setRequirement(
            "orchestrator-registration-resume-wrapper",
            childExecution("orchestrator-registration-resume-wrapper") { it.providerId == "orchestrator-resume-authenticator" },
            "REQUIRED",
        )
    }
    down {
        flows().removeExecution(
            childExecution("orchestrator-registration-resume-wrapper") { it.providerId == "orchestrator-resume-authenticator" }
        )
    }
}

step("orchestrator-registration execution anlegen") {
    up {
        flows().addExecution("orchestrator-registration", mapOf("provider" to "orchestrator-authenticator"))
        val id = childExecution("orchestrator-registration") { it.providerId == "orchestrator-authenticator" }
        setRequirement("orchestrator-registration", id, "REQUIRED")
        flows().newExecutionConfig(id, AuthenticatorConfigRepresentation().apply {
            alias = "orchestrator-registration-intent"
            config = mapOf("intent" to "register")
        }).close()
    }
    down {
        flows().removeExecution(childExecution("orchestrator-registration") { it.providerId == "orchestrator-authenticator" })
    }
}

// Bindet den neuen Flow als Realm-weiten Registrierungs-Flow und schaltet die Selbstregistrierung
// ueberhaupt frei (sonst zeigt Keycloaks Login-Seite gar keinen "Registrieren"-Link). Revert bindet
// zurueck auf Keycloaks eingebauten "registration"-Flow, nicht auf null - ein Realm ohne
// registrationFlow-Bindung waere inkonsistent, sobald registrationAllowed wieder aktiv ist.
step("registrierung freischalten und an orchestrator-registration binden") {
    up {
        updateRealm {
            setRegistrationAllowed(true)
            registrationFlow = "orchestrator-registration"
        }
    }
    down {
        updateRealm {
            setRegistrationAllowed(false)
            registrationFlow = "registration"
        }
    }
}


// ===================== V8__orchestrator_app_client =====================

// Dediziertes Gegenstueck zu V5 fuer den APP-Kanal: KcTokenProvider (DPoP-demo-xso.3) ruft den
// Custom-Grant urn:dpop-demo:account-token bislang ueber orchestrator-admin auf - denselben Client,
// den KeycloakAdminClient fuer die Admin-REST-API (User-Sync) mit realm-management manage-users/
// view-realm nutzt. AccountTokenGrantType selbst prueft keine Client-Rolle (siehe dessen eigene
// Doku) - der Grant braucht also keinerlei Admin-Rechte, bekommt sie nur zufaellig mit, weil er
// bislang denselben Client-Secret wie die Admin-REST-Aufrufe verwendet. Dieser Client trennt beide
// Verantwortlichkeiten: orchestrator-app-token darf NUR Access-/Refresh-Tokens fuer App-Kanal-
// Accounts minten, nie die Admin-REST-API aufrufen.

step("orchestrator-app-token client anlegen") {
    up {
        clients().create(ClientRepresentation().apply {
            clientId = setup.appTokenClientId
            name = setup.appTokenClientId
            setPublicClient(false)
            setStandardFlowEnabled(false)
            setDirectAccessGrantsEnabled(false)
            // Kein Service Account: der Custom-Grant mintet ein Token fuer den ANGEGEBENEN
            // account_id, nicht fuer den Client selbst - anders als orchestrator-admin (V5), das
            // seinen eigenen Service Account fuer die Admin-REST-API braucht.
            setServiceAccountsEnabled(false)
            // Wie orchestrator-admin: signierte Client-Assertion statt Secret (ADR-25).
            clientAuthenticatorType = "client-jwt"
            attributes = orchestratorClientJwtAttributes(setup)
        }).close()
    }
    down {
        clients().get(clientDbId(setup.appTokenClientId)).remove()
    }
}

// Derselbe "orchestrator-claims"-Scope wie orchestrator-admin (V5) und der Browser-Client (V4) -
// ohne ihn laeuft OrchestratorAcrAmrMapper nie fuer Tokens, die als dieser Client gemintet werden.
step("orchestrator-app-token default scopes setzen") {
    up {
        val client = clients().get(clientDbId(setup.appTokenClientId))
        listOf("profile", "email", "roles", "orchestrator-claims").forEach {
            client.addDefaultClientScope(scopeDbId(it))
        }
    }
    down {
        val client = clients().get(clientDbId(setup.appTokenClientId))
        listOf("profile", "email", "roles", "orchestrator-claims").forEach {
            client.removeDefaultClientScope(scopeDbId(it))
        }
    }
}


// ===================== V9__manage_methods_required_action =====================

// Web-Kanal-Selbstbedienung "Anmeldeverfahren verwalten" (docs/05-api.md, "Anmeldeverfahren
// verwalten im Web-Kanal"): eine Keycloak Required Action statt eines eigenen
// Clients/Flows - Keycloaks eingebauter Mechanismus fuer "bereits angemeldeter Nutzer loest
// selbst eine Zusatzaktion aus" (kc_action=orchestrator-manage-methods), erreichbar ueber den
// BESTEHENDEN dpop-demo-web-Client/orchestrator-browser-Flow (V2), ohne diesen anzufassen.
// defaultAction=false: nur ueber kc_action erreichbar, nie automatisch erzwungen.

step("orchestrator-manage-methods required action registrieren") {
    up {
        flows().registerRequiredAction(RequiredActionProviderSimpleRepresentation().apply {
            providerId = "orchestrator-manage-methods"
            name = "Anmeldeverfahren verwalten"
        })
        val rep = flows().getRequiredAction("orchestrator-manage-methods")
        rep.isEnabled = true
        rep.isDefaultAction = false
        flows().updateRequiredAction("orchestrator-manage-methods", rep)
    }
    down {
        flows().removeRequiredAction("orchestrator-manage-methods")
    }
}


// ===================== V10__browser_qr_test_flow =====================

// Zweiter Browser-Flow, NUR fürs Demo/Test: exakt derselbe Baum wie "orchestrator-browser" (V2),
// aber die LoA-1-Ebene nutzt statt des nativen Keycloak-Passwortformulars direkt den
// orchestrator-authenticator (unkonfiguriertes toolId, targetAcr=loa1) - dieselbe Technik, mit der
// LoA-2 schon heute Step-up-Kandidaten anbietet, nur eine Ebene früher angewendet.
//
// Grund: Am normalen "orchestrator-browser"-Flow (V2) ist LoA-1 bewusst festverdrahtet natives
// Passwort (Keycloak-eigene LoA-Subflow-Struktur) - dort lässt sich `auth-qr-lookup` (die
// Kalt-Einstieg-Variante von QR-Login, KcSelectMethodStrategy.candidatesFor mit account == null)
// nie erreichen, weil KcSelectMethodStrategy dort nur bei bereits bekanntem Account (Step-up ab
// LoA-2) läuft. Dieser zweite Flow macht denselben kc_select_method-Screen (inkl. auth-qr-lookup)
// schon auf LoA-1 sichtbar, ohne den produktionsnahen Haupt-Client (V2/V4) zu verändern.
//
// Eigener Flow + eigener Client (V11) statt einer Umkonfiguration des bestehenden: Keycloaks
// Flow-Bindung ist pro Client fest (ein Client bindet genau einen "browser"-Flow), ein zweiter
// Client mit eigener Bindung ist deshalb der naheliegende, Keycloak-idiomatische Weg, zwei
// unterschiedliche LoA-1-Erlebnisse gleichzeitig anzubieten.

step("orchestrator-browser-qr-test flow anlegen") {
    up {
        flows().createFlow(AuthenticationFlowRepresentation().apply {
            alias = "orchestrator-browser-qr-test"
            description = "Wie orchestrator-browser (V2), aber LoA-1 orchestrator-driven statt natives Passwort - nur für QR-Login-Tests am ersten Faktor"
            providerId = "basic-flow"
            setTopLevel(true)
            setBuiltIn(false)
        })
    }
    down {
        flows().deleteFlow(topFlowId("orchestrator-browser-qr-test"))
    }
}

step("orchestrator-resume-wrapper-qr-test subflow anlegen") {
    up {
        flows().addExecutionFlow("orchestrator-browser-qr-test", mapOf(
            "alias" to "orchestrator-resume-wrapper-qr-test",
            "type" to "basic-flow",
            "description" to "Wraps orchestrator-resume-authenticator so its AuthenticationFlowCallback registers",
        ))
        setRequirement("orchestrator-browser-qr-test", childExecution("orchestrator-browser-qr-test") { it.displayName == "orchestrator-resume-wrapper-qr-test" }, "REQUIRED")
    }
    down {
        flows().removeExecution(childExecution("orchestrator-browser-qr-test") { it.displayName == "orchestrator-resume-wrapper-qr-test" })
    }
}

step("orchestrator-resume execution (qr-test) anlegen") {
    up {
        flows().addExecution("orchestrator-resume-wrapper-qr-test", mapOf("provider" to "orchestrator-resume-authenticator"))
        setRequirement(
            "orchestrator-resume-wrapper-qr-test",
            childExecution("orchestrator-resume-wrapper-qr-test") { it.providerId == "orchestrator-resume-authenticator" },
            "REQUIRED",
        )
    }
    down {
        flows().removeExecution(childExecution("orchestrator-resume-wrapper-qr-test") { it.providerId == "orchestrator-resume-authenticator" })
    }
}

step("orchestrator-browser-forms-qr-test subflow anlegen") {
    up {
        flows().addExecutionFlow("orchestrator-browser-qr-test", mapOf(
            "alias" to "orchestrator-browser-forms-qr-test",
            "type" to "basic-flow",
            "description" to "Cookie SSO reuse vs. interactive auth, tried as alternatives",
        ))
        setRequirement("orchestrator-browser-qr-test", childExecution("orchestrator-browser-qr-test") { it.displayName == "orchestrator-browser-forms-qr-test" }, "REQUIRED")
    }
    down {
        flows().removeExecution(childExecution("orchestrator-browser-qr-test") { it.displayName == "orchestrator-browser-forms-qr-test" })
    }
}

step("browser-cookie execution (qr-test) anlegen") {
    up {
        flows().addExecution("orchestrator-browser-forms-qr-test", mapOf("provider" to "auth-cookie"))
        setRequirement("orchestrator-browser-forms-qr-test", childExecution("orchestrator-browser-forms-qr-test") { it.providerId == "auth-cookie" }, "ALTERNATIVE")
    }
    down {
        flows().removeExecution(childExecution("orchestrator-browser-forms-qr-test") { it.providerId == "auth-cookie" })
    }
}

step("orchestrator-auth-flow-qr-test subflow anlegen") {
    up {
        flows().addExecutionFlow("orchestrator-browser-forms-qr-test", mapOf(
            "alias" to "orchestrator-auth-flow-qr-test",
            "type" to "basic-flow",
            "description" to "LoA-aware branches, each driven by the OrchestratorAuthenticator",
        ))
        setRequirement(
            "orchestrator-browser-forms-qr-test",
            childExecution("orchestrator-browser-forms-qr-test") { it.displayName == "orchestrator-auth-flow-qr-test" },
            "ALTERNATIVE",
        )
    }
    down {
        flows().removeExecution(childExecution("orchestrator-browser-forms-qr-test") { it.displayName == "orchestrator-auth-flow-qr-test" })
    }
}

// ── LoA 1: orchestrator-driven statt natives Passwort - einziger Unterschied zu V2 ─────────────────

step("orchestrator-loa-1-qr-test subflow anlegen") {
    up {
        flows().addExecutionFlow("orchestrator-auth-flow-qr-test", mapOf(
            "alias" to "orchestrator-loa-1-qr-test",
            "type" to "basic-flow",
            "description" to "LoA-1 branch: orchestrator kc_select_method (offers auth-*-lookup incl. auth-qr-lookup)",
        ))
        setRequirement("orchestrator-auth-flow-qr-test", childExecution("orchestrator-auth-flow-qr-test") { it.displayName == "orchestrator-loa-1-qr-test" }, "CONDITIONAL")
    }
    down {
        flows().removeExecution(childExecution("orchestrator-auth-flow-qr-test") { it.displayName == "orchestrator-loa-1-qr-test" })
    }
}

step("loa-1 condition execution (qr-test) anlegen") {
    up {
        flows().addExecution("orchestrator-loa-1-qr-test", mapOf("provider" to "conditional-level-of-authentication"))
        val id = childExecution("orchestrator-loa-1-qr-test") { it.providerId == "conditional-level-of-authentication" }
        setRequirement("orchestrator-loa-1-qr-test", id, "REQUIRED")
        flows().newExecutionConfig(id, AuthenticatorConfigRepresentation().apply {
            alias = "orchestrator-loa-1-qr-test-condition"
            config = mapOf("loa-condition-level" to "1", "loa-max-age" to "36000")
        }).close()
    }
    down {
        flows().removeExecution(childExecution("orchestrator-loa-1-qr-test") { it.providerId == "conditional-level-of-authentication" })
    }
}

step("loa-1 orchestrator execution (qr-test) anlegen") {
    up {
        flows().addExecution("orchestrator-loa-1-qr-test", mapOf("provider" to "orchestrator-authenticator"))
        val id = childExecution("orchestrator-loa-1-qr-test") { it.providerId == "orchestrator-authenticator" }
        setRequirement("orchestrator-loa-1-qr-test", id, "REQUIRED")
        // toolId bleibt unkonfiguriert -> kc_select_method zeigt ALLE LOOKUP_AUTH-Kandidaten
        // (CandidateTools.forLookupLogin), darunter auth-qr-lookup - das ist der ganze Zweck
        // dieses Flows.
        flows().newExecutionConfig(id, AuthenticatorConfigRepresentation().apply {
            alias = "orchestrator-loa-1-qr-test-orchestrator"
            config = mapOf("targetAcr" to "loa1")
        }).close()
    }
    down {
        flows().removeExecution(childExecution("orchestrator-loa-1-qr-test") { it.providerId == "orchestrator-authenticator" })
    }
}

// ── LoA 2: unverändert wie im Haupt-Flow (V2) - Step-up-Kandidaten fürs bereits bekannte Konto ─────

step("orchestrator-loa-2-qr-test subflow anlegen") {
    up {
        flows().addExecutionFlow("orchestrator-auth-flow-qr-test", mapOf(
            "alias" to "orchestrator-loa-2-qr-test",
            "type" to "basic-flow",
            "description" to "LoA-2 branch: orchestrator step-up (account-specific candidates)",
        ))
        setRequirement("orchestrator-auth-flow-qr-test", childExecution("orchestrator-auth-flow-qr-test") { it.displayName == "orchestrator-loa-2-qr-test" }, "CONDITIONAL")
    }
    down {
        flows().removeExecution(childExecution("orchestrator-auth-flow-qr-test") { it.displayName == "orchestrator-loa-2-qr-test" })
    }
}

step("loa-2 condition execution (qr-test) anlegen") {
    up {
        flows().addExecution("orchestrator-loa-2-qr-test", mapOf("provider" to "conditional-level-of-authentication"))
        val id = childExecution("orchestrator-loa-2-qr-test") { it.providerId == "conditional-level-of-authentication" }
        setRequirement("orchestrator-loa-2-qr-test", id, "REQUIRED")
        flows().newExecutionConfig(id, AuthenticatorConfigRepresentation().apply {
            alias = "orchestrator-loa-2-qr-test-condition"
            config = mapOf("loa-condition-level" to "2", "loa-max-age" to "36000")
        }).close()
    }
    down {
        flows().removeExecution(childExecution("orchestrator-loa-2-qr-test") { it.providerId == "conditional-level-of-authentication" })
    }
}

step("loa-2 orchestrator execution (qr-test) anlegen") {
    up {
        flows().addExecution("orchestrator-loa-2-qr-test", mapOf("provider" to "orchestrator-authenticator"))
        val id = childExecution("orchestrator-loa-2-qr-test") { it.providerId == "orchestrator-authenticator" }
        setRequirement("orchestrator-loa-2-qr-test", id, "REQUIRED")
        flows().newExecutionConfig(id, AuthenticatorConfigRepresentation().apply {
            alias = "orchestrator-loa-2-qr-test-orchestrator"
            config = mapOf("targetAcr" to "loa2")
        }).close()
    }
    down {
        flows().removeExecution(childExecution("orchestrator-loa-2-qr-test") { it.providerId == "orchestrator-authenticator" })
    }
}


// ===================== V11__browser_qr_test_client =====================

// Zweiter, demo/test-only Client, gebunden an den V10-Flow (orchestrator-browser-qr-test) statt
// an den produktionsnahen "orchestrator-browser" (V4) - siehe V10s Kommentar für den Grund. Sonst
// identisch zu V4: PUBLIC + PKCE, dieselben Redirect-URIs/Web-Origins, dieselben Default-Scopes -
// nur der gebundene Flow unterscheidet sich.

step("browser-qr-test client anlegen") {
    up {
        val flowId = flows().getFlows().first { it.alias == "orchestrator-browser-qr-test" }.id
        clients().create(ClientRepresentation().apply {
            clientId = setup.qrTestClientId
            name = "${setup.qrTestClientId} (Demo: QR-Login auf LoA-1 testen)"
            setPublicClient(true)
            setStandardFlowEnabled(true)
            setDirectAccessGrantsEnabled(false)
            setServiceAccountsEnabled(false)
            redirectUris = setup.browserRedirectUris
            // "+" heisst bei Keycloak: genau die Origins der redirectUris oben - spart eine
            // zweite Liste, die mit der ersten auseinanderlaufen koennte.
            webOrigins = listOf("+")
            attributes = mapOf("pkce.code.challenge.method" to "S256")
            authenticationFlowBindingOverrides = mapOf("browser" to flowId)
        }).close()
    }
    down {
        clients().get(clientDbId(setup.qrTestClientId)).remove()
    }
}

step("browser-qr-test default scopes setzen") {
    up {
        val client = clients().get(clientDbId(setup.qrTestClientId))
        listOf("profile", "email", "roles", "web-origins", "orchestrator-claims").forEach {
            client.addDefaultClientScope(scopeDbId(it))
        }
    }
    down {
        val client = clients().get(clientDbId(setup.qrTestClientId))
        listOf("profile", "email", "roles", "web-origins", "orchestrator-claims").forEach {
            client.removeDefaultClientScope(scopeDbId(it))
        }
    }
}


// ===================== V12__stammdaten_claim_mappers =====================

// Die Stammdaten-Attribute, die KeycloakAccountSyncListener.stammdatenAttributes() seit jeher in
// den Keycloak-User schreibt, als Token-Claims sichtbar machen.
//
// Bis hierher wurden sie gepflegt und nie gelesen: der orchestrator-claims-Scope (V3) trug nur
// orchestrator-acr-amr und orchestrator-account-id, fuer personId/kvnr/geburtsdatum und die vier
// Adressfelder gab es im ganzen Realm keinen Protocol-Mapper. Name und E-Mail fielen nicht auf,
// weil der profile-Scope sie aus Keycloaks eigenen firstName/lastName/email-Feldern zieht, die
// derselbe Sync fuellt.
//
// Im orchestrator-claims-Scope und nicht am Client, aus demselben Grund wie V3: jeder spaetere
// Client bekommt dasselbe Verhalten, indem er den Scope aufnimmt (V4, V5, V8, V11 tun das bereits).
//
// Die Attributnamen sind woertlich die Schluessel aus stammdatenAttributes() - die eine Quelle
// dafuer, was ein Account nach Keycloak spiegelt. Weicht einer ab, bleibt der Claim leer, ohne
// dass etwas fehlschlaegt; deshalb steht hier dieselbe Liste und nicht eine "aehnliche".
//
// personId/kvnr sind register-gebunden und existieren nur fuer ein Konto mit PERSON_ID-Anker; die
// uebrigen fuellt ein attestierter Interessent aus eigenen Claims. Fehlende Werte laesst der
// Mapper weg, statt sie als leeren String zu setzen.

// Attribut (stammdatenAttributes()) to Claim-Name im Token.
val stammdatenClaims = listOf(
    "personId" to "person_id",
    "kvnr" to "kvnr",
    "geburtsdatum" to "geburtsdatum",
    "strasse" to "strasse",
    "hausnummer" to "hausnummer",
    "plz" to "plz",
    "ort" to "ort",
)

// Ohne diesen Schritt waeren die Mapper unten wirkungslos: Keycloaks deklaratives User Profile
// (seit 24.x Default) verwirft JEDES nicht deklarierte User-Attribut - die Admin-API nimmt den
// Schreibvorgang an, persistiert ihn aber nicht. Genau daran scheiterten die Stammdaten bisher
// unbemerkt: KeycloakAccountSyncListener schrieb sie auf jedem AccountChanged mit, und Keycloak
// warf sie jedes Mal weg. V1 dokumentiert die Falle bereits fuer orchestratorAccountId.
//
// upConfig() ist NICHT additiv, sondern beschreibt das gesamte Profil - orchestratorAccountId aus
// V1 muss deshalb hier mit aufgezaehlt werden, sonst verschwindet es und der Account-Sync findet
// seine eigenen User nicht mehr wieder (findUserId sucht ueber genau dieses Attribut).
//
// Nur "admin": geschrieben wird ausschliesslich vom Account-Sync ueber den Service-Account,
// gelesen wird ueber die Mapper unten - der Nutzer selbst soll seine Stammdaten im Konto-Formular
// weder sehen noch aendern koennen, sie gehoeren dem Register.
step("stammdaten im user profile deklarieren") {
    up {
        users().userProfile().update(
            upConfig(
                *(listOf("orchestratorAccountId") + stammdatenClaims.map { it.first })
                    .map { it to setOf("admin") }
                    .toTypedArray()
            )
        )
    }
    down {
        users().userProfile().update(upConfig("orchestratorAccountId" to setOf("admin")))
    }
}

step("stammdaten mapper anlegen") {
    up {
        val mappers = clientScopes().get(scopeDbId("orchestrator-claims")).protocolMappers
        stammdatenClaims.forEach { (attribute, claim) ->
            mappers.createMapper(ProtocolMapperRepresentation().apply {
                name = "orchestrator-stammdaten-$attribute"
                protocol = "openid-connect"
                protocolMapper = "oidc-usermodel-attribute-mapper"
                config = mapOf(
                    "user.attribute" to attribute,
                    "claim.name" to claim,
                    "jsonType.label" to "String",
                    "id.token.claim" to "true",
                    "access.token.claim" to "true",
                    "userinfo.token.claim" to "true",
                )
            }).close()
        }
    }
    down {
        val mappers = clientScopes().get(scopeDbId("orchestrator-claims")).protocolMappers
        stammdatenClaims.forEach { (attribute, _) ->
            mappers.getMappers().first { it.name == "orchestrator-stammdaten-$attribute" }.let { mappers.delete(it.id) }
        }
    }
}
