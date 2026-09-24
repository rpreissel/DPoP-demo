# ADR-14: Schema zusammengeführt — das Konto als Sperrpunkt, eine Wahrheit je Fakt

> **Nachtrag.** Der Migrationsteil dieser Entscheidung (alles in `V1__schema.sql` +
> `V2__testdata.sql`, Regeln im Dateikopf) ist durch [ADR-30](ADR-030-eine-migration-je-modul.md)
> abgelöst: ein Migrationsordner je Modul, Testdaten in `demo_seed/V16__testdata.sql`, Regeln in
> `db/migration/KONVENTIONEN.md`. Das Kontomodell unten gilt unverändert.
>
> „Schema“ meint hier den DDL-Bestand, nicht den Datenbank-Namensraum aus
> [ADR-16](ADR-016-ein-datenbankschema-je-modul-statt-namenspraefix.md) — die beiden ADRs
> widersprechen sich nicht.


**Entscheidung**: Die 37 inkrementellen Migrationen sind in `V1__schema.sql` (+ `V2__testdata.sql`)
zusammengeführt, und das Schema folgt durchgängig deklarierten Regeln (Kopf von `V1__schema.sql`,
[Betrieb](../07-betrieb.md) Abschnitt 6). Inhaltlich:

- `account` trägt nur noch `id`, `created_at`, `version`. Über diese Zeile werden Änderungen am
  aktuellen Kontozustand gesperrt (`OPTIMISTIC_FORCE_INCREMENT`).

- Aktueller Zustand liegt in Zeilen je Fakt: `account.anchor` (einziger Speicherort von PersonId und
  bestätigter E-Mail), `account.auth_method` (eine Zeile je Methodeninstanz, `EnrollmentRef` als
  Spalten). Die Historie wird nur angefügt: `account.claim`, `account.identification`.
  Die JSON-Listen `identifications`/`authentication_methods`, die Projektionsspalten
  `person_id`/`email`/`email_confirmed_at` und `ConsolidationStrategy` entfallen, weil „lokal
  konsolidiert" und „ist Anker" dieselbe Aussage geworden sind.
- **Nachtrag**: Ihr zweiter Fall (`ExternalLiveLookup`) verschwand mit ihr, obwohl „kein lokaler
  Anker" danach Stammdaten-Hoheit (`NAME`) und Modul-Hoheit (`PHONE_NUMBER`) zugleich abdeckte;
  er ist als `AttributeType.authority` (`Local`/`Personenverzeichnis`/`MethodModule`, vollständig
  aufgezählt) in `tool_api/AttributeRules.kt` zurückgeholt. **Nachtrag**: `AttributeAuthority` ist
  inzwischen ein `sealed interface`, und `Local` trägt seine `AnchorRule` selbst. Vorher standen
  Eigentümer und Ankerregeln als Flag plus nullable Feld nebeneinander, obwohl sie nie getrennt
  vorkommen — zusammengehalten von einem Test statt vom Typ. Dasselbe Muster wie bei
  `ToolDescriptor.keyBinding` ([Tool-Architektur](../03-tool-architektur.md) Abschnitt 1).
- Fremdschlüssel nur innerhalb eines Moduls; modulübergreifende Bezüge sind indizierte Spalten.
- Einheitliche Namen (`<modul>_enrollment` = `EnrollmentRef.type`, `<modul>_<tool-rolle>_data`,
  `ux_`/`ix_`, PK-Spalte `id`) und Typen (`TIMESTAMP WITH TIME ZONE`, feste Längenraster).
  **Nachtrag**: Diese Namenskonventionen sind von ADR-16 abgelöst.

**Erwogene Alternative**: Die Migrationen nur zusammenfassen und die Form des Modells unverändert lassen (JSON-Listen auf
der Kontozeile, Projektionsspalten neben den Ankern).

**Warum diese**: Bei ≥ 10 Mio. Konten und langer Lebensdauer lassen sich die JSON-Listen weder
abfragen („alle Konten mit Methode X") noch günstig schreiben (jede Änderung schreibt die ganze
Zeile). Und jede Projektionsspalte neben einem Anker ist eine zweite Stelle, die für denselben
Fakt Eindeutigkeit garantieren müsste — genau der Fehler, den A3 im Review einmal schon beheben
musste.

**Kosten**: Ein Kontoprofil braucht drei indizierte Lesezugriffe statt einem; die E-Mail im Profil
ist die normalisierte Form, die Rohschreibweise steht nur noch im Claim-Log. Wie
bei ADR-4 gilt: Diese Neubaseline ist nur ohne Produktivdaten vertretbar, danach sind Migrationen
ausschließlich additiv.

---
