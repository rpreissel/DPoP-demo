# Idee: Weitere Terminologie-Vorschläge rund um Claims/Trust

> Der eigentliche Plan dieses Dokuments ist vollständig umgesetzt und durch die Zusammenfassung des
> Schemas überholt: PersonId und E-Mail laufen über denselben Weg für Claims und Anker, und
> `TrustAnchor` → `ClaimSource`, `AnchorClass` → `TrustLevel`, `AnchorType` → `AttributeType`,
> `AccountAttribute` → `AccountClaim` sind umbenannt. Siehe [ADR-10 bis ADR-14](../12-entscheidungen.md)
> und [claims-modell-und-vertrauensanker.md](claims-modell-und-vertrauensanker.md). Offen sind nur
> noch die folgenden, davon unabhängigen Vorschläge für Namen; umgesetzt werden sollen sie derzeit
> nicht.

## Hoher Nutzen, nah am aktuellen Vorhaben

| Heute | Vorschlag | Konkretes Missverständnis | Umfang bei späterer Freigabe |
| --- | --- | --- | --- |
| `createUnidentifiedAccount()` | `createUnboundAccount()` | Eine fehlende PersonId bedeutet genau: keine Zuordnung zu einer Person im Personenverzeichnis. Es kann aber schon eine bestätigte E-Mail-Adresse oder einen anderen Nachweis geben. In einem späteren Modell nur mit Claims wäre sogar eine Identifizierung ohne Zuordnung zu einer Person möglich. | `AccountService`, Journey, Seed und Tests. Die Code-Dokumentation muss ausdrücklich sagen: keiner Person zugeordnet, aber nicht ohne Bindung an Kanal oder Gerät. Kein neues Feld für den Zustand des Kontos. |

Ein früherer Vorschlag ist umgesetzt: `Resolution.NewInteressent` heißt seit ADR-19/ADR-20
`Resolution.Unresolved`. Der Name beschreibt damit, was die Suche ergab („kein Konto gefunden“), und
nicht mehr, was daraus folgt. Der Vorschlag in der Tabelle (`createUnboundAccount`) ist noch offen. Er
ist genauer, braucht aber wegen der verschiedenen Arten von Bindung im Projekt die genannte
Code-Dokumentation. `createAccountWithoutPersonBinding` wäre länger, dafür völlig eindeutig.

## Sinnvoll, aber als eigener Schritt beim Umbenennen

| Heute | Vorschlag | Begründung und Grenze |
| --- | --- | --- |
| `orchestrator.policy.AuthEvidence.factors: List<MethodEvidence>` | `methodEvidence` | Ein Eintrag ist der Nachweis eines Verfahrens und kann mehrere `FactorType` enthalten. Verfahren und Faktor sind bei mehreren Faktoren ausdrücklich nicht dasselbe. Richtlinie, Factory, RestoreData und Tests gemeinsam umstellen; vorher prüfen, wo JSON übertragen wird, und das übertragene Format unverändert lassen. |
| `AuthEvidence` in `orchestrator.policy` und `orchestrator.session` | Wertobjekt der Richtlinie `EvidenceSnapshot`, die gespeicherte Entität heißt weiter `AuthEvidence` | Die gleichen Namen sind laut Code-Dokumentation absichtlich gewählt, erzwingen aber Aliasnamen beim Import wie `CoreAuthEvidence`. Der neue Name bezeichnet die abgeleitete Sicht der Richtlinie, ohne einen zweiten maßgeblichen Stand zu behaupten. Betrifft viele Aufrufer; nicht nebenbei beim Umbau des Kontos erledigen. |
| `AuthMethodView` für gespeicherte Einträge mit Instanz-ID | `AccountMethodRegistrationView` | Diese Objekte beschreiben eine bestimmte eingerichtete Methodeninstanz, nicht die Art des Verfahrens. Wichtig bei mehreren Geräten. Nicht `Credential` nennen: Das eigentliche Credential gehört weiterhin dem Methodenmodul und wird nur referenziert. Felder der API, IDs und die Bedeutung von `EnrollmentRef` bleiben erhalten. |
| `ChannelSession.channelAnchor` | `peerFlowBinding` | Der Wert bindet einen Nachweis von Keycloak an genau einen Anmeldeablauf. Er ist weder ein Anker, über den ein Konto gefunden wird, noch die langlebige Sitzung in Keycloak. Nur als eigenen Schritt bei den Begriffen der Assertion zwischen den Servern prüfen. Nicht nebenbei JWT-Claims oder Datenbankspalten umbenennen und die Prüfung der Bindung nicht ändern. |

## Absichtlich beibehalten oder nur dokumentarisch präzisieren

- `personId` bleibt: Das ist tatsächlich der Schlüssel der Person im Personenverzeichnis (die
  Partnernummer), nicht `accountId`.
- `AccountAnchor` bleibt der Name für einen eindeutigen Schlüssel, über den ein Konto gefunden wird.
- `Claim`, `ClaimDeclaration` und `ClaimRequirement` bleiben getrennt: ein konkretes Ergebnis, eine
  zugesicherte Fähigkeit eines Tools und eine Voraussetzung sind verschiedene Richtungen des Vertrags.
- `AcrLevel`, `EvidenceAxis`, `FactorType`, `acrFloor` und `targetAcr` bleiben getrennt. An den Unterschieden hängen Sicherheitsregeln; es sind nicht einfach doppelte Typen.
- Erledigt: `ConsolidationStrategy.ExternalLiveLookup` gibt es nicht mehr; heute steht die Hoheit als `AttributeAuthority.PersonDirectory` bzw. `MethodModule` (für `PHONE_NUMBER`) in `tool_api/AttributeRules.kt` (ADR-14/ADR-34).
- `ClaimSource` und die vorhandene Herkunft eines Nachweises `AmrSource` nicht zusammenlegen: Die
  Herkunft einer Aussage und die Herkunft eines Nachweises der Sitzung haben unterschiedliche Werte
  und Regeln.
