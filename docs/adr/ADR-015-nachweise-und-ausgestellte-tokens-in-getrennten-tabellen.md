# ADR-15: Nachweise und ausgestellte Tokens in getrennten Tabellen

**Entscheidung**: `orchestrator.auth_evidence` (was auf einem Kanal bewiesen wurde) und `orchestrator.auth_context` (was
daraus an Tokens ausgestellt wurde) sind zwei Tabellen, und die Abhängigkeit geht nur in eine Richtung:
`orchestrator.auth_context.auth_evidence_id` zeigt auf die Nachweise, nie umgekehrt. Mehrere
Token-Kontexte dürfen auf dieselben Nachweise zeigen (`AuthContextRepository.findByAuthEvidenceId`
liefert eine Liste). Abgeleitete Werte werden in keiner der beiden gespeichert: `currentAcr`
berechnet `AuthPolicy.resolveAcr` bei jedem Lesen neu aus `amr_evidence`
([Domänenmodell](../02-domaenenmodell.md) Abschnitt 7).

**Erwogene Alternative**: Eine einzige Tabelle, mit den Spalten für die Tokens neben den Nachweisen in
derselben Zeile, so wie es vor dem Keycloak-Zugang (`07e7156`) war.

**Warum diese**: Es gibt zwei Gründe, und keiner hängt davon ab, wie viele Zeilen einander
zugeordnet sind.

1. **Nicht jeder Kanal hat Tokens, aber jeder hat Nachweise.** Der KEYCLOAK-Kanal legt nie einen
   `AuthContext` an ([API](../05-api.md) Abschnitt 3); in einer
   gemeinsamen Tabelle hätte jede Zeile des Web-Kanals vier dauerhaft leere Spalten für Tokens.
2. **Das eine ist die maßgebliche Angabe, das andere nur ein Zwischenspeicher.** Darauf beruht
   `AuthEvidenceService.invalidateCachedTokens`: Ein Step-up setzt Access- und RefreshToken auf
   `null`, **während die Nachweise erhalten bleiben**.

**Kosten**: Zwei Tabellen, die sich äußerlich stark ähneln (beide mit `account_id`, `version`,
`updated_at`) und im APP-Kanal fast immer gemeinsam entstehen. Die erlaubte 1:n-Beziehung ist heute
in der Praxis immer 1:1. Warum es trotzdem zwei sind, steht in der Code-Dokumentation von
`AuthContext` und `AuthEvidence` und hier.

---
