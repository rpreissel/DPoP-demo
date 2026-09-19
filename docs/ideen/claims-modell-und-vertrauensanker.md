# Idee: Claims-Modell mit Vertrauensanker statt fester `person_id`

> **Status: umgesetzt.** Das hier entworfene Claims-Modell (`account.anchor` als einziger
> Ankerspeicher, `account.claim` als append-only Log, `tool_api/AttributeRules.kt`,
> `IdentityResolver`/`Resolution`, `ClaimRequirement`/`ClaimDeclaration` auf `ToolDescriptor`
> (seit ADR-18 generisch geprüft, nicht mehr nur für E-Mail),
> E-Mail als generischer Anker ohne `auth_email`-Sonderabhängigkeit auf `account`) ist vollständig
> im Code. Die drei fachlichen Grundfragen (Interessenten-Verzweigung, Merge-Verhalten,
> Retraktionsform) sind entschieden: [ADR-10 bis ADR-12](../12-entscheidungen.md); die
> Schema-Form: [ADR-14](../12-entscheidungen.md). `Completed.Identified` trägt `PERSON_ID` nur
> noch als Claim, kein separates Feld mehr — und seit ADR-18 gar nicht mehr zwingend: ein Tool darf
> bezeugen, ohne jemanden aufzulösen, womit der Interessent aus ADR-10 über eID tatsächlich
> erreichbar ist. Maßgeblich für den Ist-Stand ist der Code, nicht die
> SQL-Entwürfe in der Git-Historie dieser Datei. ADR-19 hat die Auflösung danach auf Anker
> allein gestellt (`restricted_id` als dritter lokaler Anker neben `person_id` und `email`,
> Attributkombination als Auflösungsschicht entfernt) — auch das ist im Code maßgeblich.

Offen geblieben ist nur die Skalierungsfrage unten — sie betrifft eine Produktivgröße
(10 Mio. Konten), die diese Demo nie erreicht, und ist bewusst als Formvorgabe für den Fall
aufgehoben, dass das Modell einmal dorthin wächst.

## Skalierung: zehn Millionen Konten

Größenordnung: 10M `account`-Zeilen, `account.claim` bei 2-8 Zeilen pro Konto also
**20-80M Zeilen**. Zwei Grundsätze, falls das Modell dorthin wächst:

- **Hot Paths bleiben typisierte Zeilen-Lookups.** Niemand liest EAV im Hot-Path - jede
  Abfrage (`account` per PK, `account.anchor` per `(attribute_type, value)`,
  `orchestrator.device_account_link` per `binding_key_ref`) bleibt B-Tree auf engen Spalten.
- **Rückwärts-Lookup** (Wert -> Account) braucht **normalisierte Werte** (kleingeschriebene
  E-Mail, kanonisierte Telefonnummer) - ein Index über Rohwerte wäre ein Fehler
  (`Foo@x.de` ≠ `foo@x.de`). `AttributeType.rule.anchor`/`normalizeAnchorValue` leisten das
  bereits pro Typ.
- **Das Log (`account.claim`) braucht keinen Rückwärts-Index** - generische Mismatch-Abfragen
  über alle Attribute sind selten und analytisch; dort genügt ein Scan oder ein gezielter
  Index, kein dauerhaft gepflegter.
- **Bestandsmigration bei 10M ist kein Einzel-Statement**: Backfill in Chunks, idempotent, mit
  Fortschrittsspur - keine Transaktion, die Locks auf Bestandstabellen hält.
- **`KeycloakAccountSyncService` lädt heute alle Accounts in den Speicher**
  (`accountService.allAccountIds()` -> `findAll()`): bei 10M ein Full-Table-Load pro Sync,
  unabhängig vom Claims-Modell ein offener Befund - muss gestaffelter Batch werden
  (seitenweise über `id`).
- **Demo bleibt unberührt**: H2 ohne Partitionierung; das oben ist Form-Vorgabe für den Fall
  eines Wachstumsschritts, keine Anweisung für den nächsten Montag.
