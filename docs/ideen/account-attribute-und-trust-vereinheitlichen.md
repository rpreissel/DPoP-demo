# Idee: Weitere Terminologie-Vorschläge rund um Claims/Trust

> Der eigentliche Implementierungsplan dieses Dokuments (PersonId/E-Mail auf denselben
> Claim-/Anker-Pfad, `TrustAnchor` → `ClaimSource`, `AnchorClass` → `TrustLevel`, `AnchorType` →
> `AttributeType`, `AccountAttribute` → `AccountClaim`) ist vollständig umgesetzt und durch die
> Schema-Konsolidierung überholt — siehe [ADR-10 bis ADR-14](../12-entscheidungen.md) und
> [claims-modell-und-vertrauensanker.md](claims-modell-und-vertrauensanker.md). Nur die folgenden,
> unabhängigen Namensvorschläge sind noch offen und ohne Implementierungsauftrag.

## Hoher Nutzen, nah am aktuellen Vorhaben

| Heute | Vorschlag | Konkretes Missverständnis | Umfang bei späterer Freigabe |
| --- | --- | --- | --- |
| `createUnidentifiedAccount()` | `createUnboundAccount()` | Fehlende PersonId bedeutet genau: keine Bindung an eine Stammdaten-Person. Es kann bereits eine bestätigte E-Mail oder andere Evidenz geben. Im späteren Claims-only-Zielbild wäre sogar eine Identifizierung ohne Personenbindung möglich. | AccountService, Journey, Seed und Tests. KDoc muss ausdrücklich sagen: ungebunden an eine Stammdaten-Person, nicht ungebunden an Kanal/Gerät. Kein neues Account-Zustandsfeld. |

Der erste dieser Vorschläge ist umgesetzt: `Resolution.NewInteressent` heißt seit ADR-19/ADR-20
`Resolution.Unresolved` - benannt ist damit die Antwort des Resolvers ("kein Konto gefunden") und
nicht mehr die Folge daraus. `createUnboundAccount` ist ebenfalls präziser,
braucht aber wegen der verschiedenen Bindungen im Projekt die genannte KDoc. Alternativ ist
`createAccountWithoutPersonBinding` länger, dafür völlig eindeutig.

## Sinnvoll, aber als separater Terminologie-Schritt

| Heute | Vorschlag | Begründung und Grenze |
| --- | --- | --- |
| `orchestrator.policy.AuthEvidence.factors: List<MethodEvidence>` | `methodEvidence` | Ein Eintrag ist ein Methodennachweis und kann mehrere `FactorType` enthalten. Methode und Faktor sind für MFA ausdrücklich nicht dasselbe. Policy, Factory, RestoreData und Tests gemeinsam umstellen; JSON-Grenzen vorab prüfen und das Wire-Format unverändert lassen. |
| `AuthEvidence` in `orchestrator.policy` und `orchestrator.session` | Policy-Wertobjekt `EvidenceSnapshot`, persistente Entity weiter `AuthEvidence` | Die identischen Namen sind laut KDoc absichtlich gewählt, erzwingen aber Import-Aliase wie `CoreAuthEvidence`. Der neue Name benennt die abgeleitete Policy-Sicht, ohne eine zweite fachliche Wahrheit zu behaupten. Betrifft viele Aufrufer: nicht nebenbei in einem Account-Refactoring machen. |
| `AuthenticationMethod` / `AuthMethodView` für gespeicherte Einträge mit Instanz-ID | `AccountMethodRegistration` / `AccountMethodRegistrationView` | Diese Objekte beschreiben eine konkrete eingerichtete Methodeninstanz, nicht den Methodentyp. Wichtig bei mehreren Geräten. Nicht `Credential` nennen: das eigentliche Credential gehört weiterhin dem Methodenmodul und wird nur referenziert. API-Felder, IDs und EnrollmentRef-Semantik erhalten. |
| `ChannelSession.channelAnchor` | `peerFlowBinding` | Der Wert bindet einen Keycloak-Peer-Nachweis an genau einen Flow; er ist weder ein Account-Suchanker noch die langlebige Keycloak-Session. Nur als eigener Schritt in der Peer-Auth-Terminologie prüfen. Kein Umbenennen von JWT-Claims/DB-Spalten nebenbei und keine Änderung der Bindungsprüfung. |

## Absichtlich beibehalten oder nur dokumentarisch präzisieren

- `personId` bleibt: Das ist tatsächlich der Schlüssel der externen Stammdaten-Person, nicht `accountId`.
- `AccountAnchor` bleibt Name für einen eindeutigen Account-Suchschlüssel.
- `Claim`, `ClaimDeclaration` und `ClaimRequirement` bleiben verschieden: konkretes Ergebnis, zugesicherte Tool-Fähigkeit und Voraussetzung sind unterschiedliche Vertragsrichtungen.
- `AcrLevel`, `EvidenceAxis`, `FactorType`, `acrFloor` und `targetAcr` bleiben getrennt. An den Unterschieden hängen Sicherheitsregeln; es sind nicht einfach doppelte Typen.
- `ConsolidationStrategy.ExternalLiveLookup` vorerst nur präziser dokumentieren: `recordClaims` führt keinen Live-Lookup aus, sondern verzichtet auf lokale Projektion; der jeweilige Leser delegiert später. Eine Umbenennung in `Delegated` wäre für `PHONE_NUMBER` voreilig, weil dessen heutige Einordnung ausdrücklich nur provisorisch ist.
- `ClaimSource` und die vorhandene Evidenz-Herkunft `AmrSource` nicht zusammenlegen: Aussageherkunft und Herkunft eines Sitzungsnachweises haben unterschiedliche Werte und Regeln.
