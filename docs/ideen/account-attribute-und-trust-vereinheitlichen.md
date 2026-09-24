# Idee: Offene Umbenennungen rund um Claims und Nachweise

> **Status: offen, derzeit nicht zur Umsetzung freigegeben** (Issue `DPoP-demo-tcwr`). Der
> ursprüngliche Plan dieses Dokuments – PersonId und E-Mail über denselben Weg für Claims und Anker,
> dazu die Umbenennungen `TrustAnchor` → `ClaimSource`, `AnchorClass` → `TrustLevel`,
> `AnchorType` → `AttributeType`, `AccountAttribute` → `AccountClaim` und
> `Resolution.NewInteressent` → `Resolution.Unresolved` – ist umgesetzt (ADR-10 bis ADR-14, ADR-19).
> Übrig sind fünf Vorschläge für Namen. Welche Namen bewusst bleiben, steht in
> [02-domaenenmodell.md](../02-domaenenmodell.md), Abschnitt 6.

Jeder Vorschlag ist ein eigener Schritt. Keiner soll nebenbei bei einer anderen Änderung passieren,
weil jeder viele Aufrufer, Tests oder übertragene Formate berührt.

| Heute | Vorschlag | Warum | Was dabei zu beachten ist |
| --- | --- | --- | --- |
| `AccountService.createUnidentifiedAccount()` | `createUnboundAccount()` | Eine fehlende PersonId heißt nur: keiner Person im Personenverzeichnis zugeordnet. Das Konto kann trotzdem schon eine bestätigte E-Mail-Adresse oder einen anderen Nachweis haben. | Betrifft `AccountService`, `JourneyActionExecutor`, den Demo-Seed und Tests. Die Code-Dokumentation muss sagen: keiner Person zugeordnet, aber an Kanal oder Gerät gebunden. Ein neues Statusfeld gibt es dafür nicht. Eindeutiger, aber länger wäre `createAccountWithoutPersonBinding`. |
| `orchestrator.policy.AuthEvidence.factors` | `methodEvidence` | Ein Eintrag ist der Nachweis eines Verfahrens und kann mehrere `FactorType` enthalten; Verfahren und Faktor sind nicht dasselbe. | Richtlinie, Factory, `RestoreData` und Tests gemeinsam umstellen. Vorher prüfen, wo JSON übertragen wird; das übertragene Format bleibt gleich. |
| `AuthEvidence` gleichnamig in `orchestrator.policy` und `orchestrator.session` | Das Wertobjekt der Richtlinie heißt `EvidenceSnapshot`, die gespeicherte Entität bleibt `AuthEvidence`. | Die gleichen Namen erzwingen heute Aliasnamen beim Import wie `CoreAuthEvidence`. | Viele Aufrufer. |
| `AuthMethodView` | `AccountMethodRegistrationView` | Die Objekte beschreiben eine bestimmte eingerichtete Methodeninstanz, nicht die Art des Verfahrens – wichtig bei mehreren Geräten. | Nicht `Credential` nennen: Das Credential gehört dem Methodenmodul. Felder der API, IDs und `EnrollmentRef` bleiben. |
| `ChannelSession.channelAnchor` | `peerFlowBinding` | Der Wert bindet einen Nachweis von Keycloak an genau einen Anmeldeablauf. Er ist weder ein Anker zum Finden eines Kontos noch die langlebige Keycloak-Sitzung. | Nur zusammen mit den Begriffen der Assertion zwischen den Servern prüfen. JWT-Claims, Datenbankspalten und die Prüfung der Bindung nicht nebenbei ändern. |
