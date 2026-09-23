# ADR-20: Ein vorläufiges Konto geht im gefundenen auf, statt den Lauf abzuweisen

**Entscheidung** (**umgesetzt**): Findet ein Identifizierungsschritt ein **anderes** Konto als das,
mit dem die Journey gerade arbeitet, dann geht das vorläufige der beiden Konten im anderen auf —
auf welcher Seite es steht, ist egal:

- Ist das Konto **der Journey** vorläufig, wechselt die Journey zum gefundenen Konto und nimmt die
  Bestätigung mit. Das ist der ident-first-Fall: `ident-eid` bestätigt, findet niemanden,
  `performIdentified` legt dafür ein Konto an — und der `ident-kvnr`-Schritt danach findet das
  echte Konto.
- Ist das **gefundene** Konto vorläufig, bleibt die Journey, wo sie ist, und übernimmt dessen
  Daten. Das ist der Fall bei „Enrollment zuerst": Die Journey arbeitet mit dem echten Konto, in
  dem gerade die Zugangsmittel entstanden sind, und der eID-Lauf findet über den
  `restricted_id`-Anker einen Rest aus einem früheren, abgebrochenen Versuch.
- Ist **keines** von beiden vorläufig, bleibt es beim `409`. Zwei echte Konten werden nicht
  nebenbei zusammengelegt.

Dieselbe Regel gilt für eine **bestätigte Adresse**, nicht nur für eine Identifizierung:
`confirm-email` beweist den Besitz eines Werts, über den das Kontomodell Konten auflöst
(`resolveByAnchor` — genau der Weg, über den `auth-email-lookup` jemanden anmeldet). Wer also mit
einem vorläufigen Konto in der Hand seine eigene Adresse bestätigt, hat damit gesagt, welches
Konto ihm gehört. Eine Vorabprüfung „Adresse schon vergeben?" gibt es deshalb nicht mehr: Vor dem
Code ist nichts bewiesen, sondern nur getippt, und die Ablehnung traf regelmäßig genau den
Richtigen.

Eine Bedingung kommt hier dazu, die eine Identifizierung nicht braucht: **Die bestätigte Identität
muss zum aufgelösten Konto passen.** Der Besitz eines Postfachs sagt „dieses Postfach gehört mir",
niemals „ich bin diese Person". Hat das Zielkonto eine Registerperson, wird die in dieser Sitzung
bestätigte Identität gegen deren Stammdaten geprüft (`IdentityResolver.attestedIdentityMatches`,
derselbe Wächter, den ADR-18 vor den Korrelationsschritt stellt) — sonst könnte, wer ein fremdes
Postfach kontrolliert, seine eigenen eID-Claims an ein fremdes Konto hängen. Hat das Zielkonto
keine Person gebunden, gibt es nichts zu prüfen, und „eines von beiden ist vorläufig" ist die ganze
Bedingung.

Was „vorläufig" heißt, steht als benannte Regel am `AccountProfile` und nicht als Bedingung an
mehreren Stellen: `isProvisional` = `isUnidentified` (keine PersonId, ADR-10) **und** es wurde nie
ein Zugangsmittel eingerichtet. Deaktivierte zählen mit: Bei einer widerrufenen Instanz hängt weiterhin die Herkunft von Claims
(`account.claim.auth_method_id`, ADR-12), und die darf man weder mitnehmen noch wegwerfen. Dieselbe Regel entscheidet, ob eine abgebrochene Journey ihr Konto löschen darf
(`JourneyService.deleteIfAbandonedUnidentified`): eine Regel, zwei Folgen, statt zweier
handgeschriebener Bedingungen, die auseinanderlaufen können.

Die Übernahme (`AccountService.absorbProvisionalAccount`) ist ausdrücklich **kein** allgemeines
„Identitätsdaten zwischen Konten verschieben". Sie verlangt ein vorläufiges Quellkonto, und genau
das macht sie harmlos. Ihre Reihenfolge gehört zur Entscheidung und ist kein Implementierungsdetail:
`account.anchor` ist je (Typ, Wert) global eindeutig (`ux_anchor_value`), also müssen die Anker der
Quelle **weg sein, bevor** dieselben Werte am Zielkonto geschrieben werden — lesen, freigeben,
löschen, schreiben. Geschrieben wird über den normalen `recordClaim`-Pfad, Claim für Claim in der
ursprünglichen Reihenfolge; Konfliktprüfung, ACR-Floors und Widerrufsregeln gelten am Zielkonto
damit unverändert, und ein Anker, den das Ziel mit demselben Wert schon hat, bleibt wirkungslos, wie
schon vorher.

Für den Kanal gilt dasselbe in umgekehrter Richtung: Nachweise und Gerätebindung werden umgehängt,
**bevor** das alte Konto gelöscht wird (`AuthEvidenceService.rebindToAccount`,
`linkDeviceToAccount`). Die Nachweise werden dabei nicht zurückgesetzt — was diese Sitzung bewiesen
hat, hat sie bewiesen; es wandert nur der Zeiger auf das Konto, die zwischengespeicherten Tokens
fallen weg. Nach dem Wechsel läuft die Registrierung noch einmal durch
`RegisterStrategy.afterIdentification`, damit das neue Konto dieselben zwei Fragen durchläuft wie
auf jedem anderen Weg dorthin: Ist dieses Gerät schon an ein anderes Konto gebunden? Und kann das
Konto einfach eine vorhandene Methode beweisen, statt eine neue einzurichten?

**Erwogene Alternative**: Die eID-Claims bis zur Bindung nur in der Journey halten (JSON-Spalte an
`auth_journey`, Overlay über ein synthetisches `AccountProfile`, Materialisierung beim ersten
schreibenden Akt). Verworfen, und nicht nur wegen des Umfangs: `IdentKvnrDescriptor.requires`
(NAME/VORNAME/GEBURTSDATUM `PROVEN`) wird gegen `ctx.account` geprüft — ohne geschriebenes Konto
lässt sich der Zuordnungsschritt gar nicht anbieten, das Overlay wäre also nicht optional, sondern
Pflicht. Das ist viel Aufwand gegen einen Fehler, der aus einem einzigen `409` besteht.

**Warum diese**: Das vorläufige Konto ist ein Nebenprodukt der Journey und gehört dem Nutzer nicht
— außer der Bestätigung, die gerade entstanden ist, trägt es nichts. Es im gefundenen Konto aufgehen
zu lassen kostet nichts und rettet genau diese Bestätigung. Es stehen zu lassen erzeugt dagegen einen
Konflikt, den der Nutzer weder verursacht hat noch auflösen kann.

**Kosten / bewusst offen**: Zwei echte Konten zusammenzulegen bleibt ungelöst und bleibt beim `409`.
Hat das Zielkonto eine andere E-Mail oder eine andere `restricted_id`, greifen die normalen
Ankerregeln: ersetzen samt Widerruf (`EMAIL`, `EID_RESTRICTED_ID`) oder abweisen (`PERSON_ID`) —
die Übernahme kopiert nichts an diesen Regeln vorbei. Zurückgezogene Claims kommen nicht mit, und
die übernommenen Claim-Zeilen tragen den Zeitpunkt der Übernahme. Wann Identität bewiesen wurde,
steht weiterhin in den `account.identification`-Zeilen; die wandern mit ihrem ursprünglichen
`identified_at` und einem `absorbedFromAccountId`-Vermerk mit.

---
