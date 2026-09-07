// Äquivalent zu infra/tofu/keycloak/main.tf: der komplette "orchestrator-browser"-Flow-Baum
// (keycloak_authentication_flow/_subflow/_execution/_execution_config). Jeder Schritt hier
// entspricht einer terraform-Ressource (Execution + ihre Config zusammen als ein Schritt); die
// Reihenfolge ist exakt die Abhängigkeitsreihenfolge aus main.tf - Keycloak vergibt die Priority
// innerhalb eines Flow-Levels automatisch nach Anlage-Reihenfolge, deshalb muss hier niemand
// manuell mit priority hantieren, solange die Schritte in dieser Reihenfolge laufen.
//
// Struktur folgt docs/ideen/web-keycloak-kanal.md #9: LoA1 = natives Keycloak-Passwort (sofort an
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
