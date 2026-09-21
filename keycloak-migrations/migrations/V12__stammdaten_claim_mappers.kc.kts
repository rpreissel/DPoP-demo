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
