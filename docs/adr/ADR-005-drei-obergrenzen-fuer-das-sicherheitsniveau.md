# ADR-5: Drei Obergrenzen für das Sicherheitsniveau

> **Stand 2026-09-23:** Die erste Obergrenze wird nicht mehr als eigene Größe gelesen; sie wirkt
> nur noch über `enrolledUnderAcr`. Siehe Nachtrag 2.

**Entscheidung**: Das erreichbare Sicherheitsniveau ist an drei unabhängigen Stellen begrenzt:
`account.identification.achieved_acr` begrenzt, was ein Account je erreichen kann;
`account.auth_method.enrolled_under_acr` begrenzt, was eine einzelne Methode beisteuern darf;
`achievedAcr` einer Session ist das Minimum aus tatsächlich Nachgewiesenem und dem, was die
verwendete Methode laut ihrem `enrolledUnderAcr` tragen darf
([Orchestrierung](../04-orchestrierung.md) Abschnitt 8, [Überblick](../01-ueberblick.md)).

**Erwogene Alternative**: Nur `achievedAcr` aus der aktuellen Session-Historie ableiten, ohne die
Enrollment-Bedingungen der einzelnen Methode rückwirkend zu berücksichtigen.

**Warum diese**: Ohne die `enrolledUnderAcr`-Begrenzung gäbe es einen Weg nach oben: Wer eine
schwache Session übernimmt (z. B. `loa1`), könnte darin eine eigene Methode einrichten und damit
dauerhaft ein höheres Niveau vortäuschen. Die dritte Grenze verhindert zusätzlich, dass eine
schwach identifizierte Person über starke Auth-Methoden ein Niveau erreicht, das ihre
Identifizierung nie hergab.

**Kosten**: Drei Stellen, an denen ein Niveau sinken kann, statt einer. Welche davon gerade
greift, lässt sich nur über `AuthEvidence` (vgl. [ADR-15](ADR-015-nachweise-und-ausgestellte-tokens-in-getrennten-tabellen.md)), `account.auth_method.enrolled_under_acr` und
`account.identification.achieved_acr` zusammen nachvollziehen.

**Nachtrag 1**: `DefaultAuthPolicy.resolveAcr` trennt die Identifizierungsstärke (IAL,
`identityAssuranceLevel`) von der Authentifizierungsstärke (AAL, `authenticatorAssuranceLevel`) —
siehe [Orchestrierung](../04-orchestrierung.md) Abschnitt 8. Der sichtbare `acr`-Wert bleibt
unverändert; die Trennung behebt einen falschen MFA-Bump. (Ursprünglich stand hier, IAL berechne
die erste Obergrenze. Das trifft nicht zu, siehe Nachtrag 2.)

**Nachtrag 2 (2026-09-23)**: `account.identification.achieved_acr` wird für keine Entscheidung
gelesen; die Tabelle ist ein reiner Audit-Nachweis (`AccountIdentification`, Klassen-Doku). IAL
zählt bewusst nur den Identitätsnachweis der **laufenden** Session, weil Identität je Session neu
bewiesen wird (`DefaultAuthPolicy.identityAssuranceLevel`). Die erste Obergrenze wirkt deshalb nur
noch mittelbar: `enrolledUnderAcr` ist das Niveau, das die Session beim Einrichten der Methode
nachgewiesen hatte (`JourneyActionExecutor`, `resolveAcr` auf dem Session-Nachweis), und darin
steckt die Identifizierung dieser Session. Eine schwach identifizierte Person kann eine starke
Methode also weiterhin nicht über ihr Identifizierungsniveau hinaus einrichten; es gibt dafür nur
keine eigene, kontoweite Grenze mehr. Faktisch greifen zwei Stellen: `enrolledUnderAcr` je Methode
und das Minimum daraus mit dem in der Session Bewiesenen (`performAcceptProof`).

---
