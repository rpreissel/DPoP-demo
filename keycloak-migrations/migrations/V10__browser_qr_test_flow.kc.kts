// Zweiter Browser-Flow, NUR fürs Demo/Test: exakt derselbe Baum wie "orchestrator-browser" (V2),
// aber die LoA-1-Ebene nutzt statt des nativen Keycloak-Passwortformulars direkt den
// orchestrator-authenticator (unkonfiguriertes toolId, targetAcr=loa1) - dieselbe Technik, mit der
// LoA-2 schon heute Step-up-Kandidaten anbietet, nur eine Ebene früher angewendet.
//
// Grund: Am normalen "orchestrator-browser"-Flow (V2) ist LoA-1 bewusst festverdrahtet natives
// Passwort (docs/ideen/web-keycloak-kanal.md #9) - dort lässt sich `auth-qr-lookup` (die
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
