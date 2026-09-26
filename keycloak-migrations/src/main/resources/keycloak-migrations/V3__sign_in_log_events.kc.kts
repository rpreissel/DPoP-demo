// ===================== V3__sign_in_log_events =====================

// Keycloak meldet Logouts an das Anmeldeprotokoll des Orchestrators (ADR-39, Nachtrag). Den Logout
// im Web-Kanal macht Keycloak allein; ohne diesen Listener kaeme er im Protokoll nicht vor. Der
// Listener (SignInLogEventListener in keycloak-extension) sendet nach dem Commit und blockiert den
// Logout nie. jboss-logging bleibt daneben aktiv.

step("anmeldeprotokoll-listener einschalten") {
    up {
        updateRealmEventsConfig(getRealmEventsConfig().apply {
            eventsListeners = (eventsListeners.orEmpty() + "orchestrator-sign-in-log").distinct()
        })
    }
    down {
        updateRealmEventsConfig(getRealmEventsConfig().apply {
            eventsListeners = eventsListeners.orEmpty() - "orchestrator-sign-in-log"
        })
    }
}
