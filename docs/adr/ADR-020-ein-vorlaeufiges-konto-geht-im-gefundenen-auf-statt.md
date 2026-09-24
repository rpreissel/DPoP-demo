# ADR-20: Ein vorläufiges Konto geht im gefundenen auf, statt den Lauf abzuweisen

**Status:** umgesetzt.

## Entscheidung

Findet ein Nachweis ein **anderes** Konto als das, mit dem die Journey gerade arbeitet, dann geht das
vorläufige der beiden Konten im anderen auf. Auf welcher Seite das vorläufige steht, spielt keine
Rolle:

- Ist das Konto **der Journey** vorläufig, wechselt die Journey zum gefundenen Konto und nimmt die
  Bestätigung mit. Das ist der Fall, in dem zuerst identifiziert wird: `ident-eid` bestätigt die
  Identität und findet niemanden, `performRecordIdentification` legt dafür ein Konto an, und der Schritt
  `ident-kvnr` danach findet das echte Konto.
- Ist das **gefundene** Konto vorläufig, bleibt die Journey bei ihrem Konto und übernimmt dessen
  Daten. Das ist der Fall im Experiment „Erst Anmeldeverfahren einrichten“: Die Journey arbeitet mit
  dem echten Konto, in dem gerade die Zugangsmittel entstanden sind, und die Identifizierung findet
  über den Anker `restricted_id` ein übrig gebliebenes Konto aus einem früheren, abgebrochenen
  Versuch.
- Ist **keines** von beiden vorläufig, bleibt es beim `409`. Zwei echte Konten werden nicht nebenbei
  zusammengelegt ([ADR-11](ADR-011-kontouebergreifender-person-id-konflikt-ist-abweisung-merge-nie.md)).

**Vorläufig** ist eine benannte Regel an `AccountProfile`: `isProvisional` = `isUnidentified` (keine
PersonId) **und** nie ein Zugangsmittel eingerichtet. Deaktivierte Zugangsmittel zählen mit, denn an
einer widerrufenen Instanz hängt weiterhin die Herkunft von Claims (`account.claim.auth_method_id`,
[ADR-12](ADR-012-ein-widerruf-ist-eine-eigene-zeile-mit-eigenem.md)). Dieselbe Regel entscheidet, ob
eine abgebrochene Journey ihr Konto löschen darf (`JourneyService.deleteIfAbandonedUnidentified`).

### Bestätigte Adresse: zwei Bedingungen mehr

Die Regel gilt auch für eine mit `confirm-email` bestätigte Adresse. Deren Besitz beweist einen Wert,
über den Konten gefunden werden (`resolveByAnchor`, derselbe Weg wie bei `auth-email-lookup`). Weil
ein Postfach aber nur sagt „dieses Postfach gehört mir“ und nie „ich bin diese Person“, kommen zwei
Bedingungen hinzu:

1. **Diese Sitzung muss die Identität schon nachgewiesen haben**
   (`JourneyActionExecutor.accountOfAttestation`; ohne Nachweis der Art `IDENTITY` antwortet sie mit
   `409` „Diese Adresse gehört bereits zu einem anderen Konto“). Sonst könnte eine neue Sitzung die
   hinterlegte Adresse eines anderen erneut bestätigen und dessen Konto samt Zugangsmitteln
   übernehmen.
2. **Die bestätigte Identität muss zum gefundenen Konto passen.** Ist das Konto einer Person im
   Personenverzeichnis zugeordnet, prüft `IdentityResolver.attestedIdentityMatches` die in dieser
   Sitzung bestätigte Identität gegen deren Stammdaten, dieselbe Prüfung wie vor der Zuordnung
   ([ADR-18](ADR-018-bestaetigen-und-zuordnen-sind-zwei-akte.md)).

Eine vorherige Prüfung „Adresse schon vergeben?“ gibt es nicht: Vor der Eingabe des Codes ist nichts
bewiesen, und die Ablehnung traf regelmäßig genau den Richtigen. Folge von Bedingung 1: Im Experiment
„Erst Anmeldeverfahren einrichten“ kommt `confirm-email` vor jeder Identifizierung und weist eine
bereits vergebene Adresse deshalb immer ab.

### Wie übernommen wird

Die Übernahme (`AccountService.absorbProvisionalAccount`) verschiebt **nicht** allgemein
Identitätsdaten zwischen Konten. Sie verlangt ein vorläufiges Quellkonto, und genau das macht sie
harmlos. Ihre Reihenfolge gehört zur Entscheidung: `account.anchor` ist je Typ und Wert über alle
Konten eindeutig (`ux_anchor_value`). Die Anker der Quelle werden deshalb gelesen, freigegeben und
gelöscht, **bevor** dieselben Werte am Zielkonto geschrieben werden. Geschrieben wird auf dem normalen
Weg über `recordClaim`, Claim für Claim in der ursprünglichen Reihenfolge; Konfliktprüfung,
Mindestniveaus der Anker und Widerrufsregeln gelten also unverändert.

Für den Kanal gilt dasselbe umgekehrt: Nachweis und Gerätebindung werden auf das neue Konto
umgestellt (`AuthEvidenceService.rebindToAccount`, `linkDeviceToAccount`), **bevor** das alte Konto
gelöscht wird. Was die Sitzung bewiesen hat, bleibt bewiesen; nur die zwischengespeicherten Tokens
entfallen. Danach läuft die Registrierung noch einmal durch `RegisterStrategy.afterIdentification`:
Ist das Gerät schon an ein anderes Konto gebunden? Kann das Konto ein vorhandenes Verfahren
nachweisen, statt ein neues einzurichten?

## Begründung

Das vorläufige Konto entsteht nur nebenbei in der Journey und enthält außer der gerade entstandenen
Bestätigung nichts. Es im gefundenen Konto aufgehen zu lassen kostet nichts und rettet genau diese
Bestätigung. Es stehen zu lassen erzeugt einen Konflikt, den der Nutzer weder verursacht hat noch
auflösen kann.

**Erwogene Alternative:** die eID-Claims bis zur Bindung nur in der Journey halten (JSON-Spalte an
`auth_journey`, darübergelegt über ein künstliches `AccountProfile`). Verworfen, weil
`IdentKvnrDescriptor.requires` gegen `ctx.account` geprüft wird: Ohne gespeichertes Konto ließe sich
die Zuordnung gar nicht anbieten. Das wäre viel Aufwand gegen einen Fehler, der nur aus einem `409`
besteht.

## Folgen

- Zwei echte Konten zusammenzuführen bleibt ungelöst; es bleibt beim `409`.
- Hat das Zielkonto eine andere E-Mail-Adresse oder `restricted_id`, gelten die normalen Regeln für
  Anker: ersetzen samt Widerruf (`EMAIL`, `EID_RESTRICTED_ID`) oder abweisen (`PERSON_ID`).
- Zurückgenommene Claims werden nicht übernommen. Übernommene Zeilen im Claim-Log tragen den
  Zeitpunkt der Übernahme; wann die Identität bewiesen wurde, steht weiter in `account.identification`
  (mit ursprünglichem `identified_at` und dem Vermerk `absorbedFromAccountId`).

## Geschichte

Bis Commit e92716c (2026-09-23) genügte bei der bestätigten Adresse „eines von beiden ist vorläufig“
zusammen mit dem Abgleich der Identität. Die Bedingung, dass die Sitzung die Identität schon
nachgewiesen haben muss, kam dazu, als sich zeigte, dass sonst eine neue Sitzung ein fremdes Konto
übernehmen konnte.
