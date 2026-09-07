// Äquivalent zu infra/tofu/keycloak/main.tf: keycloak_custom_user_federation.orchestrator_password.
// Unabhängig von allen anderen Migrationen, braucht nur das Realm aus V1.
//
// Routet den "password"-Credential-Typ für jeden federation-verlinkten User zu
// OrchestratorPasswordStorageProvider statt zu Keycloaks eingebautem JPA-Passwort-Provider - kein
// Passwort landet je in Keycloak selbst, gleiches Prinzip wie ein LDAP-Federation-Provider, der an
// sein eigenes Verzeichnis delegiert. KeycloakAdminClient.createUser sucht diese Komponente über
// ihre provider_id (die eigene id variiert pro Umgebung/Import) und setzt sie als federationLink.

step("orchestrator-password federation anlegen") {
    up {
        components().add(ComponentRepresentation().apply {
            name = "orchestrator-password"
            providerId = "orchestrator-password"
            providerType = "org.keycloak.storage.UserStorageProvider"
            config = MultivaluedHashMap<String, String>().apply {
                put("priority", listOf("0"))
                put("enabled", listOf("true"))
            }
        }).close()
    }
    down {
        val id = components().query().first {
            it.name == "orchestrator-password" && it.providerType == "org.keycloak.storage.UserStorageProvider"
        }.id
        components().removeComponent(id)
    }
}
