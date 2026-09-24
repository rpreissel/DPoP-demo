# ADR-13: Account-Domänentypen über eigene `AttributeConverter`, nicht `@Enumerated`

**Entscheidung** (umgesetzt): `AccountClaim.attributeType` und `AccountAnchor.attributeType` sind
über einen gemeinsamen JPA-`AttributeConverter` typisiert (`AttributeTypeConverter`). Er wandelt über
`wireName` in beide Richtungen um, statt `@Enumerated(EnumType.STRING)` zu nutzen, wie es das Modul
`orchestrator` für seine eigenen Enums tut. Die Quelle einer Angabe ist `AccountClaim.claimSource:
String` und bewusst nicht typisiert (siehe Kosten). Der Widerruf hat dagegen ein eigenes, typisiertes
Feld `AccountRetraction.trustAnchor: RetractionAnchor`.

**Erwogene Alternativen**:

- **`@Enumerated(EnumType.STRING)`**, einheitlich mit dem Modul `orchestrator`: verworfen.
  `account.claim.attribute_type` und `account.anchor.attribute_type` enthalten seit Jahren die
  kleingeschriebenen Namen aus `wireName` (`person_id`, `email`). `@Enumerated(STRING)` schreibt aber
  den Namen der Enum-Konstante (`PERSON_ID`) und hätte damit jede bestehende Zeile unbemerkt nicht
  mehr gefunden.
- **`claimSource` ebenfalls typisieren** (als `@JvmInline value class`): verworfen, nachdem es
  nachweislich scheiterte. Hibernate brach bei jedem Schreibzugriff mit
  `JpaSystemException: class java.lang.String cannot be cast to class ...` ab, weil es dem Konverter
  beim Zugriff über die Property einen einfachen `String` übergibt statt der verpackten Value Class.
  `AttributeType` ist ein echtes Enum und hat dieses Problem nicht.

**Warum diese**: Wird ein gespeicherter Name umbenannt, soll der Compiler das melden, statt dass über
Jahre unbemerkt Daten beschädigt werden. Mit dem eigenen Konverter statt `@Enumerated` bleiben die
gespeicherten Werte genau wie bisher, ohne dass die vorhandenen Daten umgestellt werden müssen.

**Kosten**: Es gibt zwei verschiedene Arten der Typisierung (`@Convert` hier, `@Enumerated(STRING)` im
`orchestrator`) statt einer einheitlichen. Beheben ließe sich das nur, indem man die Daten umstellt
oder den `orchestrator` ändert. `claimSource` bleibt untypisiert; das ist eine bekannte und
dokumentierte Lücke.

---
