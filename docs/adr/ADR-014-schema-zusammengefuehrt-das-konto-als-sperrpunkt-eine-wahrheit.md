# ADR-14: Schema zusammengeführt — das Konto als gemeinsame Sperre, jeder Fakt an genau einer Stelle

> **Nachtrag.** Der Migrationsteil dieser Entscheidung (alles in `V1__schema.sql` +
> `V2__testdata.sql`, Regeln im Dateikopf) ist durch [ADR-30](ADR-030-eine-migration-je-modul.md)
> abgelöst: ein Migrationsordner je Modul, Testdaten in `demo_seed/V16__testdata.sql`, Regeln in
> `db/migration/KONVENTIONEN.md`. Das Kontomodell unten gilt unverändert.
>
> „Schema“ meint hier die Gesamtheit der Tabellendefinitionen, nicht den Namensraum in der Datenbank
> aus [ADR-16](ADR-016-ein-datenbankschema-je-modul-statt-namenspraefix.md). Die beiden ADRs
> widersprechen sich also nicht.


**Entscheidung**: Die 37 aufeinander aufbauenden Migrationen sind in `V1__schema.sql` (und
`V2__testdata.sql`) zusammengefasst, und das Schema folgt durchgehend festgeschriebenen Regeln (Kopf
von `V1__schema.sql`, [Betrieb](../07-betrieb.md) Abschnitt 6). Inhaltlich:

- `account` trägt nur noch `id`, `created_at`, `version`. Über diese Zeile werden Änderungen am
  aktuellen Kontozustand gesperrt (`OPTIMISTIC_FORCE_INCREMENT`).

- Der aktuelle Zustand liegt in einer Zeile je Fakt: `account.anchor` (einziger Speicherort von PersonId und
  bestätigter E-Mail), `account.auth_method` (eine Zeile je Methodeninstanz, `EnrollmentRef` als
  Spalten). Die Historie wird nur angefügt: `account.claim`, `account.identification`.
  Die JSON-Listen `identifications`/`authentication_methods`, die abgeleiteten Spalten
  `person_id`/`email`/`email_confirmed_at` und `ConsolidationStrategy` entfallen. „Wird im Konto zu
  einem gültigen Wert zusammengefasst“ und „ist ein Anker“ bedeuten nämlich inzwischen dasselbe.
- **Nachtrag**: Mit `ConsolidationStrategy` verschwand auch ihr zweiter Fall (`ExternalLiveLookup`),
  obwohl „kein lokaler Anker“ danach zwei verschiedene Dinge abdeckte: Werte, die dem
  Personenverzeichnis gehören (`NAME`), und Werte, die einem Modul gehören (`PHONE_NUMBER`). Der Fall
  ist als `AttributeType.authority` (`Local`/`PersonDirectory`/`MethodModule`, vollständig
  aufgezählt; `PersonDirectory` hieß bis ADR-34 `Personenverzeichnis`) in `tool_api/AttributeRules.kt` zurückgeholt. **Nachtrag**: `AttributeAuthority` ist
  inzwischen ein `sealed interface`, und `Local` trägt seine `AnchorRule` selbst. Vorher standen
  Eigentümer und Ankerregeln als Schalter plus optionales Feld nebeneinander, obwohl sie nie getrennt
  vorkommen; zusammengehalten wurden sie von einem Test statt vom Typ. Dasselbe Muster wie bei
  `ToolDescriptor.keyBinding` ([Tool-Architektur](../03-tool-architektur.md) Abschnitt 1).
- Fremdschlüssel gibt es nur innerhalb eines Moduls; Bezüge über Modulgrenzen hinweg sind Spalten mit
  Index.
- Einheitliche Namen (`<modul>_enrollment` = `EnrollmentRef.type`, `<modul>_<tool-rolle>_data`,
  `ux_`/`ix_`, Primärschlüsselspalte `id`) und Typen (`TIMESTAMP WITH TIME ZONE`, feste Stufen für
  Längen).
  **Nachtrag**: Diese Namenskonventionen sind von ADR-16 abgelöst.

**Erwogene Alternative**: Die Migrationen nur zusammenfassen und das Modell unverändert lassen
(JSON-Listen in der Zeile des Kontos, abgeleitete Spalten neben den Ankern).

**Warum diese**: Bei 10 Millionen Konten und mehr und langer Lebensdauer lassen sich die JSON-Listen
weder abfragen („alle Konten mit Verfahren X“) noch günstig schreiben (jede Änderung schreibt die
ganze Zeile). Und jede abgeleitete Spalte neben einem Anker ist eine zweite Stelle, die für denselben
Fakt Eindeutigkeit garantieren müsste. Genau diesen Fehler musste ein früherer Review-Befund (A3)
schon einmal beheben.

**Kosten**: Ein Kontoprofil braucht drei Lesezugriffe über einen Index statt einem. Die E-Mail-Adresse
im Profil ist die normalisierte Form; die ursprüngliche Schreibweise steht nur noch im Claim-Log. Wie
bei ADR-4 gilt: Diese neue Ausgangsbasis ist nur ohne Produktivdaten vertretbar. Danach dürfen
Migrationen nur noch etwas hinzufügen.

---
