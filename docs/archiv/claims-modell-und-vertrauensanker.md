# Idee: Claims-Modell mit Vertrauensanker statt fester `person_id`

> **Status: umgesetzt.** Das hier entworfene Claims-Modell (`account.anchor` als einziger
> Ankerspeicher, `account.claim` als Log, das nur angefügt wird, `tool_api/AttributeRules.kt`,
> `IdentityResolver`/`Resolution`, `ClaimRequirement`/`ClaimDeclaration` auf `ToolDescriptor`
> (seit ADR-18 generisch geprüft, nicht mehr nur für E-Mail),
> E-Mail als generischer Anker ohne `auth_email`-Sonderabhängigkeit auf `account`) ist vollständig
> im Code. Die drei fachlichen Grundfragen (Verzweigung für Interessenten, Verhalten
> beim Zusammenführen, Form des Widerrufs) sind entschieden: [ADR-10 bis ADR-12](../12-entscheidungen.md); die
> Form des Schemas: [ADR-14](../12-entscheidungen.md). `Completed.Identified` enthält `PERSON_ID` nur
> noch als Claim, nicht mehr als eigenes Feld, und seit ADR-18 nicht einmal mehr zwingend: Ein Tool
> darf bestätigen, ohne eine Person zu finden. Erst damit ist der Interessent aus ADR-10 über die eID
> tatsächlich erreichbar. Maßgeblich für den Ist-Stand ist der Code, nicht die
> SQL-Entwürfe in der Git-Historie dieser Datei. ADR-19 hat die Suche nach dem Konto danach
> ausschließlich auf Anker gestellt: `restricted_id` wurde der dritte lokale Anker neben `person_id` und
> `email` (seit ADR-34 kommt `versnr` hinzu), und die Suche über die Kombination von Attributen ist
> entfernt. Auch dafür ist der Code maßgeblich.

Offen geblieben ist nur die Frage der Größe unten. Sie betrifft einen Umfang im Produktivbetrieb
(10 Millionen Konten), den diese Demo nie erreicht. Sie ist bewusst als Vorgabe für den Fall
aufgehoben, dass das Modell einmal so groß wird.

## Skalierung: zehn Millionen Konten

Größenordnung: 10M `account`-Zeilen, `account.claim` bei 2-8 Zeilen pro Konto also
**20-80M Zeilen**. Zwei Grundsätze, falls das Modell dorthin wächst:

- **Die häufigen Abfragen lesen weiterhin gezielt einzelne Zeilen mit festen Spalten.** Auf diesen
  Wegen wird nie über ein Modell aus Attribut-Wert-Paaren gelesen. Jede Abfrage (`account` über den
  Primärschlüssel, `account.anchor` über `(attribute_type, value)`,
  `orchestrator.device_account_link` über `binding_key_ref`) nutzt weiter einen B-Baum-Index auf
  schmalen Spalten.
- **Die Suche vom Wert zum Konto** braucht **normalisierte Werte** (kleingeschriebene E-Mail-Adresse,
  Telefonnummer in einheitlicher Form). Ein Index über die ursprünglichen Werte wäre ein Fehler
  (`Foo@x.de` ≠ `foo@x.de`). `AttributeType.anchorRule`/`normalizeAnchorValue` leisten das
  bereits pro Typ.
- **Das Log (`account.claim`) braucht keinen Index für die Suche vom Wert zum Konto.** Allgemeine
  Abfragen nach Abweichungen über alle Attribute sind selten und dienen der Auswertung. Dafür genügt
  ein Durchsuchen der Tabelle oder ein eigens angelegter Index, der nicht dauerhaft gepflegt wird.
- **Bestehende Daten bei 10 Millionen Konten umzustellen, ist keine einzelne SQL-Anweisung.** Man
  stellt sie in wiederholbaren Portionen um und hält den Fortschritt fest, statt mit einer einzigen
  Transaktion Sperren auf den Tabellen zu halten.
- **`KeycloakAccountSyncService` lädt heute alle Konten in den Speicher**
  (`accountService.allAccountIds()` -> `findAll()`). Bei 10 Millionen Konten wäre das bei jedem
  Abgleich die ganze Tabelle. Unabhängig vom Claims-Modell ist das ein offener Befund; der Abgleich
  muss dann in Portionen laufen (seitenweise über `id`).
- **Die Demo bleibt davon unberührt**: H2 ohne Partitionierung. Das oben ist eine Vorgabe für den
  Fall, dass das Modell wächst, und keine Aufgabe für die nächste Zeit.
