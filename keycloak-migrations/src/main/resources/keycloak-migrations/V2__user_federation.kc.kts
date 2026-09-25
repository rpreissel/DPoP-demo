// ===================== V2__user_federation =====================

// Keycloak liest die Konten, statt sie zu spiegeln (Review 2026-09, P-3; Fahrplan Phase E 26).
// Die orchestrator-Komponente aus V1 (V6-Abschnitt) wird zur Nutzer-Federation ohne Import - und
// bekommt dafür eine FESTE Id (USER_STORAGE_COMPONENT_ID): aus ihr bildet Keycloak die Id jedes
// föderierten Nutzers und damit das sub. Die Konfiguration (OrchestratorSettings) wandert unverändert mit.
//
// Cache: höchstens 60 s. Keycloak hält einen föderierten Nutzer im eigenen Cache; eine geänderte
// E-Mail oder ein Name ist spätestens nach einer Minute sichtbar, ein Nutzer kostet höchstens einen
// Orchestrator-Aufruf je Minute. Gelöschte Konten räumt der Orchestrator sofort ab
// (orchestrator-accounts/{accountId}, AccountRemovalResource).
//
// Das Entfernen der alten Komponente löscht die Nutzer, die an ihr hingen - die bisherigen
// Spiegel. Das ist gewollt: Sitzungen und sub der Spiegel verfallen einmalig.

step("orchestrator-komponente als feste federation") {
    up {
        val old = components().query().first {
            it.providerId == "orchestrator" && it.providerType == "org.keycloak.storage.UserStorageProvider"
        }
        components().removeComponent(old.id)
        components().add(ComponentRepresentation().apply {
            id = USER_STORAGE_COMPONENT_ID
            name = old.name
            providerId = old.providerId
            providerType = old.providerType
            config = MultivaluedHashMap<String, String>(old.config).apply {
                put("cachePolicy", listOf("MAX_LIFESPAN"))
                put("maxLifespan", listOf("60000"))
            }
        }).close()
    }
    down {
        val current = components().component(USER_STORAGE_COMPONENT_ID).toRepresentation()
        components().removeComponent(USER_STORAGE_COMPONENT_ID)
        components().add(ComponentRepresentation().apply {
            name = current.name
            providerId = current.providerId
            providerType = current.providerType
            config = MultivaluedHashMap<String, String>(current.config).apply {
                remove("cachePolicy")
                remove("maxLifespan")
            }
        }).close()
    }
}
