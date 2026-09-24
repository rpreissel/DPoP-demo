# ADR-13: Account-Domänentypen über eigene `AttributeConverter`, nicht `@Enumerated`

**Status:** Aufgegangen in
[`db/migration/KONVENTIONEN.md`](../../src/main/resources/db/migration/KONVENTIONEN.md), Punkt
„Aufzählungswerte speichern“ unter „Typen und Namen“. Es ist eine Speicherregel, keine Architekturentscheidung.

Kurz: Das Konto speichert Attributtypen über einen eigenen `AttributeConverter` mit ihrem `wireName`
(`person_id`), nicht mit dem Namen der Enum-Konstante; die Quelle einer Angabe (`claimSource`) bleibt
bewusst ein einfacher `String`.
