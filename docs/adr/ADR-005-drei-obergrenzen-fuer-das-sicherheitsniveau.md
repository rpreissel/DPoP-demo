# ADR-5: Drei Obergrenzen für das Sicherheitsniveau

**Entscheidung**: Das erreichbare Sicherheitsniveau ist an drei unabhängigen Stellen begrenzt:
`account.identification.achieved_acr` begrenzt, was ein Account je erreichen kann;
`account.auth_method.enrolled_under_acr` begrenzt, was eine einzelne Methode beisteuern darf;
`achievedAcr` einer Session ist das Minimum aus tatsächlich Nachgewiesenem und dem, was die
verwendete Methode laut ihrem `enrolledUnderAcr` tragen darf
([Orchestrierung](04-orchestrierung.md) Abschnitt 8, [Überblick](01-ueberblick.md)).

**Erwogene Alternative**: Nur `achievedAcr` aus der aktuellen Session-Historie ableiten, ohne die
Enrollment-Bedingungen der einzelnen Methode rückwirkend zu berücksichtigen.

**Warum diese**: Ohne die `enrolledUnderAcr`-Begrenzung gäbe es einen Weg nach oben: Wer eine
schwache Session übernimmt (z. B. `loa1`), könnte darin eine eigene Methode einrichten und damit
dauerhaft ein höheres Niveau vortäuschen. Die dritte Grenze verhindert zusätzlich, dass eine
schwach identifizierte Person über starke Auth-Methoden ein Niveau erreicht, das ihre
Identifizierung nie hergab.

**Kosten**: Drei Stellen, an denen ein Niveau sinken kann, statt einer. Welche davon gerade
greift, lässt sich nur über `AuthContext`, `account.auth_method.enrolled_under_acr` und
`account.identification.achieved_acr` zusammen nachvollziehen.

**Nachtrag**: `DefaultAuthPolicy.resolveAcr` berechnet die erste Obergrenze inzwischen als eigene
Größe (IAL, `identityAssuranceLevel`), getrennt von der Authentifizierungsstärke (AAL,
`authenticatorAssuranceLevel`) — siehe [Orchestrierung](04-orchestrierung.md) Abschnitt 8.
Der sichtbare `acr`-Wert und diese ADR bleiben unverändert; die Trennung behebt einen
falschen MFA-Bump.

---
