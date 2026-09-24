# ADR-12: Ein Widerruf ist eine eigene Zeile mit eigenem Vertrauensanker

> **Stand 2026-09-23:** Abgeleitete Spalten gibt es nicht mehr, und Widerrufe werden an mehr als
> einer Stelle gelesen. Siehe Nachtrag 3.

**Entscheidung** (**umgesetzt**, [Idee](../ideen/claims-modell-und-vertrauensanker.md)): Ein zurückgenommener Wert (KVNR abgemeldet, E-Mail-Adresse verworfen) wird in einer eigenen Zeile festgehalten: `account.retraction(account_id, attribute_type, normalized_value, trust_anchor, reason, retracted_at)`. Diese Zeile ist selbst eine Angabe mit eigenem Vertrauensanker: WER den Wert zurücknimmt, dazu Grund und Zeitpunkt. Das Log (`account.claim`) wird ausschließlich ergänzt, nie geändert. Welcher Wert gilt, ergibt sich aus „Angaben minus Widerrufe“; so werden die abgeleiteten Spalten und `account.anchor` aktuell gehalten. Die Zeile in `account.anchor` wird dabei gelöscht, denn diese Tabelle ist abgeleitet und kein Log. Widerrufe kommen nie über den Vertrag der Tools: `ToolOutcome` kennt nur positive Ergebnisse.

**Erwogene Alternative**: Markierungsspalten (`retracted_at`/`retracted_by`) direkt in der Zeile des
Claims. Das ergäbe eine einzige Tabelle und die einfachste Abfrage, wäre aber die einzige Stelle, an
der eine Zeile im Log nachträglich geändert wird.

**Warum diese**: Das Log ist die maßgebliche Quelle und wird nie überschrieben. Diese Regel darf
keine Ausnahme bekommen. Jede Änderung an einer bestehenden Zeile macht es schwerer,
nachträglich zu sagen, was wann galt. Eine Widerrufszeile belegt ihre Herkunft genauso wie eine
Angabe und hält den Vertrag der Tools frei von negativen Ergebnissen.

**Kosten**: Zwei Formen statt einer. Der „gültige Wert“ ist immer eine Differenz über zwei Tabellen,
und jede Stelle, die gültige Werte bestimmt oder abfragt, muss den Widerruf berücksichtigen. Heute
ist das genau eine Stelle (der Abgleich der Attribute in `IdentityMatchingService`), dort mit einem
`not exists` über einen eigenen Index.

**Nachtrag zur Umsetzung**: Damit ein Widerruf weiß, *was* er zurücknimmt, enthält jede Zeile im
Claim-Log die `auth_method_id` der Methodeninstanz, bei deren Einrichtung sie entstanden ist (`null`
bei Tools zur Identifizierung). Wird ein Verfahren entfernt (`AccountDeletionService.revokeMethod`),
nimmt `retractClaimsOf` genau dessen Angaben zurück, aber **nur die mit
`AttributeAuthority.MethodModule`**. Ein Anker (`EMAIL`) oder ein Stammdatum (`NAME`) bleibt über das
Credential hinaus bestehen; sonst hätte das Entfernen des E-Mail-Verfahrens die Anmeldung mit
Passwort (`ClaimRequirement(EMAIL, PROVEN)`) unmöglich gemacht. Der Widerruf selbst trägt
`RetractionAnchor.ACCOUNT_MANAGEMENT`. Das ist bewusst von `ClaimSource` getrennt, weil ein Tool nie
widerrufen darf.

**Zweiter Nachtrag zur Umsetzung**: Zwei Ergänzungen halten das Log handhabbar.

- `account.claim` protokolliert Änderungen, nicht Durchläufe. `recordClaims` überspringt eine
  Angabe, die in gleicher Form schon gilt (gleicher Typ, normalisierter Wert, Quelle und
  Methodeninstanz). Die Methodeninstanz bleibt Teil dieses Vergleichs. Wird ein bekannter Wert mit
  einem neuen Verfahren eingerichtet, wird er deshalb trotzdem protokolliert; sonst ginge der Wert
  verloren, sobald die alte Instanz widerrufen wird.
- Seit ADR-19 widerruft auch das Ersetzen eines Ankers an derselben Stelle (`EMAIL`,
  `EID_RESTRICTED_ID`) den alten Wert (`ACCOUNT_MANAGEMENT`, „anker-ersetzt“). Sonst stünde ein
  ersetzter Wert für immer als gültig im Log. Die Abfrage in `findEstablished`, die widerrufene
  Angaben ausschließt, vergleicht dafür die Zeitpunkte (`r.retracted_at >= a.established_at`): Ein
  Widerruf entkräftet nur Angaben, die vor ihm liegen; ein danach neu bestätigter Wert gilt wieder
  (die Folge a → b → a endet bei a).

**Offen und bewusst nicht mitentschieden**: Der Widerruf macht einen Wert *ungültig*, er *löscht*
ihn nicht. Mit dem normalisierten Wert entsteht in der Widerrufszeile sogar eine zweite lesbare
Kopie. Naheliegend wäre eine Aufbewahrungsfrist, nach der Claim- und Widerrufszeile gemeinsam
gelöscht werden. Der Nachweis für das Audit hängt nicht daran, denn `account.identification` hält
Verfahren, Niveau und Zeitpunkt ohne die Werte der Attribute fest.

**Nachtrag 3 (2026-09-23)**:
- Seit [ADR-14](ADR-014-schema-zusammengefuehrt-das-konto-als-sperrpunkt-eine-wahrheit.md) gibt es
  keine abgeleiteten Spalten mehr; aktuell gehalten wird nur noch `account.anchor`.
- Die unter „Kosten“ genannte einzige Stelle (der Abgleich der Attribute in
  `IdentityMatchingService`) ist mit ADR-19 entfallen. `findEstablished` wird heute an mehreren
  Stellen aufgerufen: in `AccountService` (bestätigte Angaben, Übernahme der Claims in `recordClaims`,
  `absorbProvisionalAccount`, Anzeige) und in `IdentityMatchingService` (bestätigte Identität). Alle
  nutzen dieselbe Abfrage; die Differenz wird also weiterhin an genau einer Stelle gebildet, nur mit
  mehr Aufrufern.

**Nachtrag 4 (ADR-34, 2026-09-24)**: Es gibt einen vierten Auslöser mit eigenem Anker: Ändert oder
entfernt das Personenverzeichnis KVNR oder Versicherungsnummer, nimmt
`AccountService.applyDirectoryChange` den alten Wert mit `RetractionAnchor.PERSON_DIRECTORY` zurück. Das ist kein Tool; die Regel „ein Tool
widerruft nie“ bleibt unberührt.

---
