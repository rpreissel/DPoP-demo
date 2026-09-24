# ADR-20: Ein vorläufiges Konto geht im gefundenen auf, statt den Lauf abzuweisen

> **Stand 2026-09-23:** Für eine bestätigte Adresse gilt eine zusätzliche Bedingung; siehe Nachtrag.

**Entscheidung** (**umgesetzt**): Findet ein Identifizierungsschritt ein **anderes** Konto als das,
mit dem die Journey gerade arbeitet, dann geht das vorläufige der beiden Konten im anderen auf. Auf
welcher Seite das vorläufige steht, spielt keine Rolle:

- Ist das Konto **der Journey** vorläufig, wechselt die Journey zum gefundenen Konto und nimmt die
  Bestätigung mit. Das ist der Fall, in dem zuerst identifiziert wird: `ident-eid` bestätigt die
  Identität und findet niemanden, `performIdentified` legt dafür ein Konto an, und der Schritt
  `ident-kvnr` danach findet das echte Konto.
- Ist das **gefundene** Konto vorläufig, bleibt die Journey bei ihrem Konto und übernimmt dessen
  Daten. Das ist der Fall im Experiment „Erst Anmeldeverfahren einrichten“: Die Journey arbeitet mit
  dem echten Konto, in dem gerade die Zugangsmittel entstanden sind. Die Identifizierung per eID
  findet über den Anker `restricted_id` ein übrig gebliebenes Konto aus einem früheren, abgebrochenen
  Versuch.
- Ist **keines** von beiden vorläufig, bleibt es beim `409`. Zwei echte Konten werden nicht
  nebenbei zusammengelegt.

Dieselbe Regel gilt für eine **bestätigte Adresse**, nicht nur für eine Identifizierung:
`confirm-email` beweist, dass jemand einen Wert besitzt, über den das Kontomodell Konten findet
(`resolveByAnchor`, genau der Weg, über den `auth-email-lookup` jemanden anmeldet). Wer also mit
einem vorläufigen Konto seine eigene Adresse bestätigt, hat damit gesagt, welches Konto ihm gehört.
Eine vorherige Prüfung „Adresse schon vergeben?“ gibt es deshalb nicht mehr: Vor der Eingabe des Codes
ist nichts bewiesen, sondern nur etwas eingetippt, und die Ablehnung traf regelmäßig genau den
Richtigen.

Eine Bedingung kommt hier dazu, die eine Identifizierung nicht braucht: **Die bestätigte Identität
muss zum aufgelösten Konto passen.** Der Besitz eines Postfachs sagt „dieses Postfach gehört mir“,
niemals „ich bin diese Person“. Ist das Zielkonto einer Person im Personenverzeichnis zugeordnet, wird die in dieser Sitzung
bestätigte Identität gegen deren Stammdaten geprüft (`IdentityResolver.attestedIdentityMatches`,
dieselbe Prüfung, die ADR-18 vor den Schritt der Zuordnung setzt). Sonst könnte jemand, der ein
fremdes Postfach kontrolliert, seine eigenen eID-Claims an ein fremdes Konto hängen. Ist dem Zielkonto
keine Person zugeordnet, gibt es nichts zu prüfen, und „eines von beiden ist vorläufig“ ist die ganze
Bedingung.

Was „vorläufig“ heißt, steht als benannte Regel an `AccountProfile` und nicht als Bedingung an
mehreren Stellen: `isProvisional` = `isUnidentified` (keine PersonId, ADR-10) **und** es wurde nie
ein Zugangsmittel eingerichtet. Deaktivierte zählen mit: An einer widerrufenen Instanz hängt weiterhin die Herkunft von Claims
(`account.claim.auth_method_id`, ADR-12), und die darf man weder mitnehmen noch wegwerfen. Dieselbe
Regel entscheidet, ob eine abgebrochene Journey ihr Konto löschen darf
(`JourneyService.deleteIfAbandonedUnidentified`). So gibt es eine Regel mit zwei Folgen statt zweier
von Hand geschriebener Bedingungen, die auseinanderlaufen können.

Die Übernahme (`AccountService.absorbProvisionalAccount`) ist ausdrücklich **kein** allgemeines
„Identitätsdaten zwischen Konten verschieben". Sie verlangt ein vorläufiges Quellkonto, und genau
das macht sie harmlos. Ihre Reihenfolge gehört zur Entscheidung und ist kein Detail der Umsetzung:
`account.anchor` ist je Typ und Wert über alle Konten eindeutig (`ux_anchor_value`). Die Anker der
Quelle müssen deshalb **weg sein, bevor** dieselben Werte am Zielkonto geschrieben werden: lesen,
freigeben, löschen, schreiben. Geschrieben wird auf dem normalen Weg über `recordClaim`, Claim für
Claim in der ursprünglichen Reihenfolge. Die Prüfung auf Konflikte, die Mindestniveaus der Anker und
die Regeln für Widerrufe gelten am Zielkonto damit unverändert. Ein Anker, den das Zielkonto schon mit
demselben Wert hat, bewirkt wie bisher nichts.

Für den Kanal gilt dasselbe in umgekehrter Richtung: Nachweise und Gerätebindung werden auf das neue
Konto umgestellt, **bevor** das alte Konto gelöscht wird (`AuthEvidenceService.rebindToAccount`,
`linkDeviceToAccount`). Die Nachweise werden dabei nicht zurückgesetzt: Was diese Sitzung bewiesen
hat, bleibt bewiesen. Nur der Verweis auf das Konto ändert sich, und die zwischengespeicherten Tokens
entfallen. Nach dem Wechsel läuft die Registrierung noch einmal durch
`RegisterStrategy.afterIdentification`, damit das neue Konto dieselben zwei Fragen durchläuft wie
auf jedem anderen Weg dorthin: Ist dieses Gerät schon an ein anderes Konto gebunden? Und kann das
Konto einfach ein vorhandenes Verfahren nachweisen, statt ein neues einzurichten?

**Erwogene Alternative**: Die eID-Claims bis zur Bindung nur in der Journey halten: als JSON-Spalte an
`auth_journey`, darübergelegt über ein künstliches `AccountProfile` und erst beim ersten Schreiben in
ein echtes Konto überführt. Verworfen, und nicht nur wegen des Umfangs: `IdentKvnrDescriptor.requires`
(NAME, VORNAME und GEBURTSDATUM mit `PROVEN`) wird gegen `ctx.account` geprüft. Ohne gespeichertes
Konto ließe sich der Schritt der Zuordnung gar nicht anbieten; das darübergelegte Profil wäre also
nicht freiwillig, sondern Pflicht. Das ist viel Aufwand gegen einen Fehler, der nur aus einem einzigen
`409` besteht.

**Warum diese**: Das vorläufige Konto entsteht nur nebenbei in der Journey und gehört nicht wirklich
dem Nutzer. Außer der Bestätigung, die gerade entstanden ist, enthält es nichts. Es im gefundenen Konto aufgehen
zu lassen kostet nichts und rettet genau diese Bestätigung. Es stehen zu lassen, erzeugt dagegen einen
Konflikt, den der Nutzer weder verursacht hat noch auflösen kann.

**Kosten / bewusst offen**: Zwei echte Konten zusammenzuführen bleibt ungelöst; es bleibt beim `409`.
Hat das Zielkonto eine andere E-Mail-Adresse oder eine andere `restricted_id`, gelten die normalen
Regeln für Anker: ersetzen samt Widerruf (`EMAIL`, `EID_RESTRICTED_ID`) oder abweisen (`PERSON_ID`).
Die Übernahme kopiert nichts an diesen Regeln vorbei. Zurückgenommene Claims werden nicht übernommen,
und die übernommenen Zeilen im Claim-Log tragen den Zeitpunkt der Übernahme. Wann die Identität bewiesen wurde,
steht weiterhin in den Zeilen von `account.identification`. Sie wandern mit ihrem ursprünglichen
`identified_at` und einem Vermerk `absorbedFromAccountId` mit.

**Nachtrag (2026-09-23)**: Für die **bestätigte Adresse** ist „eines von beiden ist vorläufig“
seit e92716c nicht mehr die ganze Bedingung. Zusätzlich muss diese Sitzung bereits die
Identität nachgewiesen haben (`JourneyActionExecutor.accountOfAttestation`: ohne
`EvidenceAxis.IDENTITY` → `409` „Diese Adresse gehoert bereits zu einem anderen Konto“). Die
Bestätigung einer Adresse allein ist schwächer als eine Identifizierung und darf eine Sitzung nie auf
ein fremdes Konto umstellen. Sonst könnte eine neue Sitzung die bereits hinterlegte Adresse eines
anderen erneut bestätigen und dessen Konto samt Zugangsmitteln übernehmen. Die Folge:
Der erste Schritt im Experiment „Erst Anmeldeverfahren einrichten“ (`confirm-email`, vor jeder
Identifizierung) weist eine bereits vergebene Adresse immer ab. Der Fall aus dem Entscheidungstext
(eID ohne Treffer im Personenverzeichnis, danach `confirm-email`) funktioniert weiter, weil dort die
Identifizierung vorausging.

---
