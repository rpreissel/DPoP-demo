# ADR-12: Ein Widerruf ist eine eigene Zeile mit eigenem Vertrauensanker

**Entscheidung** (**umgesetzt**, [Idee](ideen/claims-modell-und-vertrauensanker.md)): Ein zurückgezogener Wert (KVNR abgemeldet, E-Mail verworfen) wird als eigene Zeilenform festgehalten — `account.retraction(account_id, attribute_type, normalized_value, trust_anchor, reason, retracted_at)` — und ist selbst eine Angabe mit eigenem Vertrauensanker: WER ruft zurück, plus Grund und Zeitpunkt. Das Log (`account.claim`) bleibt strikt append-only; die Konsolidierung rechnet "Angaben minus Widerrufe" und hält Projektionsspalten und `account.anchor` aktuell (die Anker-Zeile wird gelöscht — die Anker-Tabelle ist Projektion, nicht Log). Widerrufe kommen nie über den Tool-Vertrag: `ToolOutcome` kennt nur positive Ergebnisse.

**Erwogene Alternative**: Flag-Spalten (`retracted_at`/`retracted_by`) direkt auf der
Claim-Zeile — eine Tabelle, einfachste Abfrage, aber die einzige Nicht-Append-Mutation im Log.

**Warum diese**: Das Log ist die Quelle der Wahrheit und wird nie überschrieben — diese Regel
darf keine Ausnahme bekommen. Jede Änderung an einer bestehenden Zeile macht es schwerer,
nachträglich zu sagen, was wann galt. Eine Widerrufszeile trägt ihre Herkunft genauso nachweisbar
wie eine Angabe und hält den Tool-Vertrag frei von negativen Ergebnissen.

**Kosten**: Zwei Formen statt eine — "gültiger Wert" ist immer eine Subtraktion über zwei
Tabellen, und jeder Konsolidierungs- und Abfragepfad muss den Widerruf mitdenken; heute ist das
genau ein Pfad (der Attributabgleich in `IdentityMatchingService`), dort ein `not exists` über
einen eigenen Index.

**Nachtrag zur Umsetzung**: Damit ein Widerruf weiß, *was* er zurückzieht,
trägt jede Claim-Zeile die `auth_method_id` der Methodeninstanz, bei deren Einrichtung sie
entstanden ist (`null` bei Identifizierungs-Tools). Beim
Entfernen einer Methode (`AccountDeletionService.revokeMethod`) zieht `retractClaimsOf` genau
deren Angaben zurück — aber **nur die mit `AttributeAuthority.MethodModule`**: Ein Anker
(`EMAIL`) oder ein Stammdaten-Attribut (`NAME`) überlebt das Credential, sonst hätte das
Entfernen der E-Mail-Methode den Passwort-Login
(`ClaimRequirement(EMAIL, PROVEN)`) zerstört. Der Widerruf selbst ist
`RetractionAnchor.ACCOUNT_MANAGEMENT` — bewusst von `ClaimSource` getrennt, weil
ein Tool nie widerrufen darf.

**Zweiter Nachtrag zur Umsetzung**: Zwei Ergänzungen, die das Log praktikabel halten:
Erstens ist `account.claim` ein Change-Log, kein Run-Log — `recordClaims` überspringt eine
Angabe, die identisch bereits gilt (gleicher Typ, normalisierter Wert, Quelle und
Methodeninstanz); die Methodeninstanz bleibt Teil des Schlüssels, damit ein Neu-Enrollment eines
bekannten Werts trotzdem loggt (sonst würde die Revokation der alten Instanz den Wert verlieren).
Zweitens widerruft seit ADR-19 auch das Ersetzen eines Ankers an derselben Stelle (`EMAIL`,
`EID_RESTRICTED_ID`) den alten Wert (`ACCOUNT_MANAGEMENT`, „anker-ersetzt") — sonst würde ein
ersetzter Wert für immer als gültig im Log stehen. Der Anti-Join in `findEstablished` vergleicht dafür
die Zeitpunkte (`r.retracted_at >= a.established_at`): Ein Widerruf entkräftet nur Angaben, die
vor ihm liegen; ein danach neu bestätigter Wert gilt wieder (die Folge a → b → a endet bei a).

**Offen und bewusst nicht mitentschieden**: Der Widerruf macht einen Wert *ungültig*, er
*löscht* ihn nicht, und mit dem normalisierten Wert entsteht in der Widerrufszeile eine zweite
lesbare Kopie. Naheliegend wäre eine Aufbewahrungsfrist, nach der Claim-
und Widerrufszeile gemeinsam gelöscht werden; der Audit-Nachweis hängt nicht daran,
`account.identification` hält Verfahren, LoA und Zeitpunkt ohne die Attributwerte fest.


---
