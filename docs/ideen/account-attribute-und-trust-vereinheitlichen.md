# Idee: Weitere Terminologie-Vorschläge rund um Claims/Trust

> Der eigentliche Implementierungsplan dieses Dokuments (PersonId/E-Mail auf denselben
> Claim-/Anker-Pfad, `TrustAnchor` → `ClaimSource`, `AnchorClass` → `TrustLevel`, `AnchorType` →
> `AttributeType`, `AccountAttribute` → `AccountClaim`) ist vollständig umgesetzt und durch die
> Schema-Konsolidierung überholt — siehe [ADR-10 bis ADR-14](../12-entscheidungen.md) und
> [claims-modell-und-vertrauensanker.md](claims-modell-und-vertrauensanker.md). Nur die folgenden,
> unabhängigen Namensvorschläge sind noch offen und ohne Implementierungsauftrag.

## Hoher Nutzen, nah am aktuellen Vorhaben

| Heute | Vorschlag | Konkretes Missverstaendnis | Umfang bei spaeterer Freigabe |
| --- | --- | --- | --- |
| `createUnidentifiedAccount()` | `createUnboundAccount()` | Fehlende PersonId bedeutet genau: keine Bindung an eine Stammdaten-Person. Es kann bereits eine bestaetigte E-Mail oder andere Evidenz geben. Im spaeteren Claims-only-Zielbild waere sogar eine Identifizierung ohne Personenbindung moeglich. | AccountService, Journey, Seed und Tests. KDoc muss ausdruecklich sagen: ungebunden an eine Stammdaten-Person, nicht ungebunden an Kanal/Geraet. Kein neues Account-Zustandsfeld. |

Der erste dieser Vorschlaege ist eingeloest: `Resolution.NewInteressent` heisst seit ADR-19/ADR-20
`Resolution.Unresolved` - benannt ist damit die Antwort des Resolvers ("kein Konto gefunden") und
nicht mehr die Folge daraus. `createUnboundAccount` ist ebenfalls praeziser,
braucht aber wegen der verschiedenen Bindungen im Projekt die genannte KDoc. Alternativ ist
`createAccountWithoutPersonBinding` laenger, dafuer voellig eindeutig.

## Sinnvoll, aber als separater Terminologie-Schritt

| Heute | Vorschlag | Begruendung und Grenze |
| --- | --- | --- |
| `orchestrator.policy.AuthEvidence.factors: List<MethodEvidence>` | `methodEvidence` | Ein Eintrag ist ein Methodennachweis und kann mehrere `FactorType` enthalten. Methode und Faktor sind fuer MFA ausdruecklich nicht dasselbe. Policy, Factory, RestoreData und Tests gemeinsam umstellen; JSON-Grenzen vorab pruefen und Drahtformat erhalten. |
| `AuthEvidence` in `orchestrator.policy` und `orchestrator.session` | Policy-Wertobjekt `EvidenceSnapshot`, persistente Entity weiter `AuthEvidence` | Die identischen Namen sind laut KDoc absichtlich gewaehlt, erzwingen aber Import-Aliase wie `CoreAuthEvidence`. Der neue Name benennt die abgeleitete Policy-Sicht, ohne eine zweite fachliche Wahrheit zu behaupten. Breiter Aufruferradius: nicht als beilaufiges Account-Refactoring. |
| `AuthenticationMethod` / `AuthMethodView` fuer gespeicherte Eintraege mit Instanz-ID | `AccountMethodRegistration` / `AccountMethodRegistrationView` | Diese Objekte beschreiben eine konkrete eingerichtete Methodeninstanz, nicht den Methodentyp. Wichtig bei mehreren Geraeten. Nicht `Credential` nennen: das eigentliche Credential gehoert weiterhin dem Methodenmodul und wird nur referenziert. API-Felder, IDs und EnrollmentRef-Semantik erhalten. |
| `ChannelSession.channelAnchor` | `peerFlowBinding` | Der Wert bindet einen Keycloak-Peer-Nachweis an genau einen Flow; er ist weder ein Account-Suchanker noch die langlebige Keycloak-Session. Nur als separater Peer-Auth-Begriffsschritt pruefen. Kein Umbenennen von JWT-Claims/DB-Spalten nebenbei und keine Aenderung der Bindungspruefung. |

## Absichtlich beibehalten oder nur dokumentarisch praezisieren

- `personId` bleibt: Das ist tatsaechlich der Schluessel der externen Stammdaten-Person, nicht `accountId`.
- `AccountAnchor` bleibt Name fuer einen eindeutigen Account-Suchschluessel.
- `Claim`, `ClaimDeclaration` und `ClaimRequirement` bleiben verschieden: konkretes Ergebnis, zugesicherte Tool-Faehigkeit und Voraussetzung sind unterschiedliche Vertragsrichtungen.
- `AcrLevel`, `EvidenceAxis`, `FactorType`, `acrFloor` und `targetAcr` bleiben getrennt. Die Unterschiede tragen Sicherheitsregeln und sind keine blosse Typenduplikation.
- `ConsolidationStrategy.ExternalLiveLookup` vorerst nur praeziser dokumentieren: `recordClaims` fuehrt keinen Live-Lookup aus, sondern verzichtet auf lokale Projektion; der jeweilige Leser delegiert spaeter. Eine Umbenennung in `Delegated` waere fuer `PHONE_NUMBER` voreilig, weil dessen heutige Einordnung ausdruecklich nur provisorisch ist.
- `ClaimSource` und die vorhandene Evidenz-Herkunft `AmrSource` nicht zusammenlegen: Aussageherkunft und Herkunft eines Sitzungsnachweises haben unterschiedliche Werte und Regeln.
