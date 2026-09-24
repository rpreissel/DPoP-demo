# ADR-5: Drei Obergrenzen für das Sicherheitsniveau

> **Stand 2026-09-23:** Die erste Obergrenze wird nicht mehr als eigene Größe gelesen; sie wirkt
> nur noch über `enrolledUnderAcr`. Siehe Nachtrag 2.

**Entscheidung**: Das erreichbare Sicherheitsniveau ist an drei voneinander unabhängigen Stellen
begrenzt:

- `account.identification.achieved_acr` begrenzt, was ein Konto überhaupt erreichen kann.
- `account.auth_method.enrolled_under_acr` begrenzt, was ein einzelnes Verfahren beitragen darf.
- `achievedAcr` einer Sitzung ist das Kleinere von zwei Werten: dem tatsächlich Nachgewiesenen und
  dem, was das verwendete Verfahren laut seinem `enrolledUnderAcr` erreichen darf.

Einzelheiten: [Orchestrierung](../04-orchestrierung.md) Abschnitt 8, [Überblick](../01-ueberblick.md).

**Erwogene Alternative**: `achievedAcr` nur aus dem ableiten, was in der aktuellen Sitzung bisher
geschehen ist, ohne zu berücksichtigen, unter welchen Bedingungen ein Verfahren einmal eingerichtet
wurde.

**Warum diese**: Ohne die Begrenzung durch `enrolledUnderAcr` gäbe es einen Weg nach oben: Wer eine
schwache Sitzung übernimmt (z. B. auf `loa1`), könnte darin ein eigenes Verfahren einrichten und damit
dauerhaft ein höheres Niveau vortäuschen. Die dritte Grenze verhindert außerdem, dass eine schwach
identifizierte Person über starke Anmeldeverfahren ein Niveau erreicht, das ihre Identifizierung nie
hergab.

**Kosten**: Es gibt drei Stellen, an denen ein Niveau sinken kann, statt einer. Welche davon gerade
wirkt, lässt sich nur mit `AuthEvidence` (vgl. [ADR-15](ADR-015-nachweise-und-ausgestellte-tokens-in-getrennten-tabellen.md)), `account.auth_method.enrolled_under_acr` und
`account.identification.achieved_acr` zusammen nachvollziehen.

**Nachtrag 1**: `DefaultAuthPolicy.resolveAcr` trennt die Stärke der Identifizierung (IAL,
`identityAssuranceLevel`) von der Stärke der Anmeldung (AAL, `authenticatorAssuranceLevel`), siehe
[Orchestrierung](../04-orchestrierung.md) Abschnitt 8. Der sichtbare `acr`-Wert bleibt gleich; die
Trennung verhindert, dass mehrere Verfahren das Niveau fälschlich anheben. (Ursprünglich stand hier, IAL berechne
die erste Obergrenze. Das trifft nicht zu, siehe Nachtrag 2.)

**Nachtrag 2 (2026-09-23)**: `account.identification.achieved_acr` wird für keine Entscheidung
gelesen; die Tabelle ist nur ein Audit-Nachweis (siehe die Dokumentation der Klasse
`AccountIdentification`). IAL zählt bewusst nur den Identitätsnachweis der **laufenden** Sitzung,
weil die Identität in jeder Sitzung neu bewiesen wird (`DefaultAuthPolicy.identityAssuranceLevel`).
Die erste Obergrenze wirkt deshalb nur noch indirekt: `enrolledUnderAcr` ist das Niveau, das die
Sitzung beim Einrichten des Verfahrens nachgewiesen hatte (`JourneyActionExecutor`, `resolveAcr` auf
dem Nachweis der Sitzung), und darin steckt die Identifizierung dieser Sitzung. Eine schwach
identifizierte Person kann ein starkes Verfahren also weiterhin nicht über ihr Niveau der
Identifizierung hinaus einrichten; es gibt dafür nur keine eigene Grenze für das ganze Konto mehr.
Tatsächlich wirken zwei Stellen: `enrolledUnderAcr` je Verfahren und das Kleinere aus diesem Wert
und dem in der Sitzung Bewiesenen (`performAcceptProof`).

---
