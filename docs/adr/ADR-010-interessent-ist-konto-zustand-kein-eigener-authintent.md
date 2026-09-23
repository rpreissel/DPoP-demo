# ADR-10: Interessent ist Konto-Zustand, kein eigener AuthIntent

**Entscheidung** (**umgesetzt**, [Idee](../ideen/claims-modell-und-vertrauensanker.md)): Es gibt keinen eigenen `AuthIntent.INTERESSENT`. Ein Interessent — ein Konto, das nur über bestätigte Claims identifiziert ist, ohne `person_id`-Bindung — ist eine Beobachtung über den Ausgang einer Identifizierung, kein wählbares Ziel. Die `REGISTER`-Journey (und jeder andere Intent, der Identifizierungen durchläuft) verzweigt auf das Auflösungs-Ergebnis (`Resolution`: `ExistingAccount` / `Unresolved`): Anker-Treffer bindet wie heute, ohne Anker-Treffer führt das Konto ohne `person_id` fort (seit ADR-19 gibt es keinen dritten Ausgang mehr).

**Erwogene Alternative**: Ein eigener `AuthIntent` mit eigener Journey, eigenen States und eigener Strategie — begründbar, falls Interessenten eine abweichende Politik bräuchten.

**Warum diese**: `AuthIntent` benennt nach eigener Definition
([AuthIntent.kt](../../src/main/kotlin/com/example/dpop/orchestrator/kernel/AuthIntent.kt)) ein Ziel
samt Strategie, nie eine Beschreibung dessen, was ein Lauf geworden ist. Der
Journey-Verlauf ist für beide Ausgänge strukturell identisch, nur die Konto-Auflösung
selbst unterscheidet sich. Der heutige `Action.RecordIdentification`-Handler behandelt den Fall `personId == null`
bereits als Verzweigung innerhalb der bestehenden Journey (REGISTER "Enrollment zuerst",
[Orchestrierung](../04-orchestrierung.md) Abschnitt 2). Ein eigener Intent würde zudem jede künftige Verzweigung doppelt führen (`STEP_UP`,
`RE_IDENTIFY` auf Interessenten-Konten).

**Kosten**: Was nur für Interessenten gilt, steht als Verzweigung in den bestehenden Strategien
(analog `ConfirmDeviceRebind`) statt in einem eigenen Strategy-Objekt — die Strategien bekommen
dadurch mehr Fallunterscheidungen.

---
