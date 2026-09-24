# ADR-11: Ein Anker, der schon einem anderen Konto gehört, wird abgewiesen; Konten werden nie automatisch zusammengeführt

**Status**: umgesetzt.

**Entscheidung**: Will ein Konto einen Anker schreiben (`person_id`, `email`, `versnr`,
`restricted_id`), dessen Wert schon einem anderen Konto gehört, wird das abgewiesen (`409`, „Dieser
`<typ>`-Wert gehoert bereits zu einem anderen Konto“). Dabei wird nichts übernommen: keine Claim-Zeile,
kein Anker. Konten werden nie automatisch zusammengeführt; das wäre eine Aufgabe des Betreibers.

Die Regel sichert die Datenbank für alle Wege ab, auf denen geschrieben wird:
`ux_anchor_value UNIQUE (attribute_type, normalized_value)` auf `account.anchor`
(`account/V2__account.sql`). Die einzige Schreibstelle ist `AccountService.recordAnchor`; binden zwei
Vorgänge gleichzeitig, wird der unterlegene vollständig zurückgerollt und erhält ebenfalls `409`.

Innerhalb **eines** Kontos gilt zusätzlich: Die `person_id` ist nach der ersten Bindung unveränderlich
(`AnchorRule.allowsReplacement = false`). Ein zweiter, abweichender `person_id`-Claim wird deshalb
ebenfalls abgewiesen.

**Ausnahme**: Ein vorläufiges Konto geht im gefundenen auf; wann ein Konto vorläufig ist und welche
Bedingungen dafür gelten, regelt [ADR-20](ADR-020-ein-vorlaeufiges-konto-geht-im-gefundenen-auf-statt.md).
Zwischen zwei echten Konten bleibt es bei der Abweisung.

Wie ein Konto über KVNR, Partnernummer oder Versicherungsnummer gefunden wird, regeln
[ADR-19](ADR-019-aufloesung-nur-ueber-anker-die-eid-restricted-id.md) und
[ADR-34](ADR-034-personenverzeichnis-meldet-aenderungen.md).

**Erwogene Alternative**: Die bestätigte Aussage trotzdem protokollieren und nur verweigern, dass sie
zum gültigen Wert wird (der Konflikt als abfragbarer Zustand); oder eine Warteschlange zur Prüfung
durch Menschen.

**Begründung**: Zwei verschiedene Menschen dauerhaft zu verknüpfen ist der teuerste Fehler, den dieses
Modell machen kann. Das zu vermeiden wiegt schwerer als der Verlust der bestätigten Aussage. Die
Rangfolge, nach der Werte zusammengefasst werden (erst die Art des Ankers, dann die Aktualität), gilt
innerhalb eines Kontos und endet an der Grenze zum nächsten.

**Folgen und Kosten**: Der betroffene Nutzer kommt nicht von selbst weiter. Solange es keine Funktion
zum Zusammenführen gibt, bleibt der Fall eine Aufgabe für den Support.

**Geschichte**: Ursprünglich war `person_id` eine eigene Spalte mit einem Teilindex
`UNIQUE(person_id)`. Seit [ADR-14](ADR-014-schema-zusammengefuehrt-das-konto-als-sperrpunkt-eine-wahrheit.md)
ist sie ein gewöhnlicher Anker wie `email`, nur mit dem höchsten Rang; die Abweisung läuft über
denselben Weg wie für alle Anker. Dabei wurden auch Lücken geschlossen: Der Anker wird geschrieben,
bevor irgendetwas anderes entsteht, und `resolveByAnchor` prüft die Anker in fester Reihenfolge ihrer
Bindungsstärke. Die Ausnahme für vorläufige Konten kam mit ADR-20 hinzu.
