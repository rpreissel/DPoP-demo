# ADR-15: Nachweise und ausgestellte Tokens in getrennten Tabellen

**Entscheidung**: `orchestrator.auth_evidence` (was auf einem Kanal bewiesen wurde) und `orchestrator.auth_context` (was
daraus an Tokens ausgestellt wurde) sind zwei Tabellen mit einseitiger Abhängigkeit:
`orchestrator.auth_context.auth_evidence_id` zeigt auf die Nachweise, nie umgekehrt; mehrere Token-Kontexte
dürfen auf dieselbe Evidenz zeigen (`AuthContextRepository.findByAuthEvidenceId` liefert eine
Liste). Abgeleitete Größen werden in keiner gespeichert: `currentAcr` berechnet
`AuthPolicy.resolveAcr` bei jedem Lesen neu aus `amr_evidence`
([Domänenmodell](../02-domaenenmodell.md) Abschnitt 7).

**Erwogene Alternative**: Eine Tabelle — die Token-Spalten neben den Nachweisen in derselben
Zeile, so wie es vor der kc-Fassade (`07e7156`) auch war.

**Warum diese**: Zwei Gründe, die beide nicht an der Kardinalität hängen.

1. **Nicht jeder Kanal hat Tokens, aber jeder hat Nachweise.** Der KEYCLOAK-Kanal legt nie einen
   `AuthContext` an ([API](../05-api.md) Abschnitt 3); in einer
   gemeinsamen Tabelle hätte jede Web-Kanal-Zeile vier dauerhaft leere Token-Spalten.
2. **Das eine ist Wahrheit, das andere Cache.** Davon lebt
   `AuthEvidenceService.invalidateCachedTokens`: Ein Step-up setzt Access- und RefreshToken auf
   `null`, **während die Nachweise stehen bleiben**.

**Kosten**: Zwei Tabellen, die sich äußerlich stark ähneln (beide mit `account_id`, `version`,
`updated_at`) und im APP-Kanal praktisch immer gemeinsam entstehen — die erlaubte 1:n-Beziehung
ist heute durchgehend eine 1:1-Beziehung. Der Grund steht in den KDocs von
`AuthContext`/`AuthEvidence` und hier.

---
