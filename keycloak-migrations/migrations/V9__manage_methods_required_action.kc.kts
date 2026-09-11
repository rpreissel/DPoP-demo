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
