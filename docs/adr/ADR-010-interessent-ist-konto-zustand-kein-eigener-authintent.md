# ADR-10: Interessent ist Konto-Zustand, kein eigener AuthIntent

**Entscheidung** (**umgesetzt**, [Idee](../ideen/claims-modell-und-vertrauensanker.md)): Es gibt keinen eigenen `AuthIntent.INTERESSENT`. Ein Interessent ist ein Konto, das nur über bestätigte Claims identifiziert ist, ohne zugeordnete `person_id`. Das beschreibt, wie eine Identifizierung ausgegangen ist; ein Ziel, das man wählen könnte, ist es nicht. Die Journey `REGISTER` (und jeder andere Intent, der eine Identifizierung durchläuft) verzweigt nach dem Ergebnis der Suche nach dem Konto (`Resolution`: `ExistingAccount` oder `Unresolved`): Passt ein Anker, wird das Konto gebunden wie bisher; passt keiner, geht es mit dem Konto ohne `person_id` weiter. Seit ADR-19 gibt es keinen dritten Ausgang mehr.

**Erwogene Alternative**: Ein eigener `AuthIntent` mit eigener Journey, eigenen Zuständen und eigener Strategie. Das wäre begründet, wenn für Interessenten andere Regeln gälten.

**Warum diese**: `AuthIntent` benennt nach eigener Definition
([AuthIntent.kt](../../src/main/kotlin/com/example/dpop/orchestrator/kernel/AuthIntent.kt)) ein Ziel
samt Strategie, nie eine Beschreibung dessen, was aus einem Durchlauf geworden ist. Der Ablauf der
Journey ist für beide Ausgänge gleich aufgebaut; nur die Suche nach dem Konto unterscheidet sich.
Die Behandlung von `Action.RecordIdentification` deckt den Fall `personId == null` schon heute als
Verzweigung innerhalb der bestehenden Journey ab (bei `REGISTER` im Experiment „Erst
Anmeldeverfahren einrichten“, [Orchestrierung](../04-orchestrierung.md) Abschnitt 2). Ein eigener
Intent müsste außerdem jede künftige Verzweigung doppelt führen (`STEP_UP` und `RE_IDENTIFY` für
Konten von Interessenten).

**Kosten**: Was nur für Interessenten gilt, steht als Verzweigung in den bestehenden Strategien (wie
bei `ConfirmDeviceRebind`) und nicht in einer eigenen Strategie. Die Strategien enthalten dadurch mehr
Fallunterscheidungen.

---
