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
