# ADR-13: Account-Domänentypen über eigene `AttributeConverter`, nicht `@Enumerated`

**Entscheidung** (umgesetzt): `AccountClaim.attributeType` und `AccountAnchor.attributeType` sind
über einen gemeinsamen JPA-`AttributeConverter` typisiert (`AttributeTypeConverter`), der über
`wireName` hin- und zurückkonvertiert — nicht über `@Enumerated(EnumType.STRING)`, das der `orchestrator`-Modul für
seine eigenen Enums nutzt. Die Quelle einer Angabe heißt `AccountClaim.claimSource: String`
(bewusst ungetypt, siehe Kosten); der Widerruf trägt dagegen ein eigenes, typisiertes
`AccountRetraction.trustAnchor: RetractionAnchor`.

**Erwogene Alternativen**:

- **`@Enumerated(EnumType.STRING)`**, konsistent mit dem `orchestrator`-Modul: verworfen, weil
  `account.claim.attribute_type`/`account.anchor.attribute_type` seit Jahren Wire-Names in
  Kleinschreibung tragen (`person_id`, `email`), `@Enumerated(STRING)` aber den
  Enum-Konstantennamen (`PERSON_ID`) schreibt und damit jede Bestandszeile unbemerkt nicht mehr
  gefunden hätte.
- **`claimSource` ebenfalls typisieren** (eine `@JvmInline value class`): verworfen
  nach einem verifizierten Fehlschlag — Hibernate scheiterte bei jedem Schreibzugriff mit
  `JpaSystemException: class java.lang.String cannot be cast to class ...`, weil der
  Property-Access-Pfad dem Konverter eine rohe
  `String`-Instanz statt der geboxten Value Class durchreicht. `AttributeType` (ein
  echtes Enum) hat dieses Problem nicht.

**Warum diese**: Eine Umbenennung im Wire-Format soll der Compiler melden, statt über Jahre
unbemerkt Daten zu beschädigen. Der eigene Konverter statt `@Enumerated`
erhält dabei exakt das bestehende Wire-Format, ohne Migration der Bestandsdaten.

**Kosten**: Zwei verschiedene Typisierungsmuster im selben Modul (`@Convert` hier,
`@Enumerated(STRING)` im `orchestrator`) statt eines einheitlichen — auflösbar nur durch
Datenmigration oder Umstellung des `orchestrator`. `claimSource` bleibt ungetypt — eine bekannte,
dokumentierte Lücke.

---
