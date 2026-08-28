# Idee: Claims-Modell mit Vertrauensanker statt fester `person_id`

Status: **nicht umgesetzt, nur festgehalten**. Entstanden aus dem Gespräch beim Bau von
`ident-eid` ([06-ablaeufe.md](../06-ablaeufe.md) #2, `ident-fsc`). Kein ADR (noch keine
Entscheidung, nur eine Spur, der man später folgen kann) - deshalb hier unter `ideen/`, nicht unter
[12-entscheidungen.md](../12-entscheidungen.md).

## Die Idee

Ein Konto identifizierende Attribute nicht als feste Spalten (`account.person_id BIGINT NOT NULL`),
sondern als eigene Zeilen speichern - jede mit ihrem eigenen Vertrauensanker. Das gilt einheitlich
für **alle** Attribute, `person_id`/KVNR eingeschlossen, nicht als deren Sonderfall:

```sql
CREATE TABLE account_attribute (
    account_id BIGINT NOT NULL REFERENCES account(id),
    attribute_type VARCHAR(50) NOT NULL,   -- "person_id", "kvnr", "name", "vorname",
                                            -- "geburtsdatum", "phone_number", ...
    value VARCHAR(255) NOT NULL,
    trust_anchor VARCHAR(50) NOT NULL,     -- "ext_stammdaten", "ident-eid", "ident-fsc",
                                            -- "self-reported", ...
    established_at TIMESTAMP NOT NULL
);
```

`person_id` verliert seinen Sonderstatus: eine Zeile wie jede andere
(`attribute_type=person_id, trust_anchor=ext_stammdaten`). Ein Interessent ohne KVNR hat einfach
keine solche Zeile - kein Sonderfall, kein `NULL`-Workaround, einfach: diese Quelle hat (noch)
nichts beigetragen.

`value` bleibt bewusst knapp (`VARCHAR(255)`), kein `TEXT`/JSON: die vollen Rohdaten eines ID-Laufs
(das komplette `ClaimedIdentity`-Objekt, `auditDetails`) landen nicht hier, sondern weiterhin
ausschließlich im bestehenden `session_event`-Audit-Trail. `account_attribute` hält nur die
einzelnen, schon aufgeschlüsselten Werte, die auch tatsächlich für Konsolidierung/Matching
gebraucht werden.

### Zwei Schichten: Log plus gepflegte Projektion

`account_attribute` ist damit **nicht** die einzige Ablage, sondern nur das anhängende, nie
überschriebene Log jeder Behauptung. Zusätzlich bleibt eine normale, typisierte Tabelle für die
wichtigsten bekannten Attribute bestehen - weiterhin `account` mit echten Spalten (`person_id
BIGINT`, `kvnr VARCHAR(20)`, ...) - bei jeder Änderung an `account_attribute` **synchron**, in
derselben Transaktion, neu konsolidiert. Kein neues Muster, sondern **Event Sourcing mit einem Read
Model** (bzw. CQRS): die Quelle der Wahrheit ist das Log der Behauptungen, die typisierte Tabelle
ist eine abgeleitete, aktiv gepflegte Projektion davon - nie selbst die Wahrheit, aber die
schnelle, typsichere Sicht darauf, die jeder bestehende Lesepfad unverändert weiterbenutzt
(`AccountService`, `DeviceAccountLink.findLinkedAccountId`, `Identified.personId`, ...).

Die Projektion löst damit die klassischen EAV-Schwächen, ohne dass ein Lesepfad sich ändert:

- **Typsicherheit**: die Projektionstabelle hat echte Spalten mit echten Typen, genau wie heute.
  Nur das Log selbst bleibt lose typisiert - aber niemand liest das Log im Hot-Path.
- **Eindeutigkeits-Constraints**: die Projektionstabelle kann `UNIQUE (person_id)` tragen. Das ist
  sogar eine Verbesserung gegenüber heute: `account.person_id` trägt aktuell keine
  `UNIQUE`-Constraint, nur einen Index (`idx_account_person_id`) - die Eindeutigkeit ist schon
  heute nur ein Anwendungs-Invariant (`findOrCreateAccount`: erst suchen, dann anlegen), keine
  DB-Garantie. Die Projektion kann das nachrüsten.
- **Joins/Indizes**: unverändert gegenüber heute, weil jeder bestehende Lesepfad weiter aus der
  Projektionstabelle liest, nicht aus `account_attribute`.

### Konsolidierungsregel

Bei einem Konfliktfall - zwei `trust_anchor`s behaupten unterschiedliche Werte für dasselbe
Attribut desselben Accounts - wird immer automatisch aufgelöst, nie blockiert: die Projektion
nimmt den neuesten Wert (`established_at`). Das ist die einfachste Regel und passt zur synchronen
Konsolidierung. Eine eigene Konflikt-Tabelle braucht es dafür nicht: `account_attribute` wird nie
überschrieben, jede frühere Behauptung jedes Ankers bleibt im Log stehen und ist damit von selbst
dokumentiert - "wer hat wann was behauptet" lässt sich jederzeit nachvollziehen, ohne dass die
Konsolidierung dafür etwas Eigenes mitführen muss. Eine ausgefeiltere Regel (z. B. eine feste
Rangfolge der Anker, `ext_stammdaten` > `ident-eid` > `ident-fsc` > `self-reported`) ist eine
spätere Verfeinerung, kein Blocker für die erste Version.

Die **kontoübergreifende** Variante - derselbe `person_id`-Wert an zwei verschiedenen Accounts -
fängt dieselbe Konsolidierung ohne eigenen Mechanismus mit auf: trägt die Projektionstabelle
`UNIQUE (person_id)`, schlägt der Konsolidierungsschritt für den zweiten Account beim Schreiben
fehl (oder wird davor per Check erkannt). Anders als beim einfachen Wertekonflikt sollte "neuester
gewinnt" hier aber nicht unbesehen gelten: zwei Accounts, die denselben `person_id`-Wert
beanspruchen, sind vermutlich kein Fall für automatische Auflösung, sondern ein Zeichen, dass zwei
Accounts eigentlich einer sein sollten - was genau dann passiert, ist noch offen (siehe unten).

### Vorbild

Kein Neuentwurf, sondern die Übertragung eines bekannten Musters:

- **OIDC/SAML-Claims**: Ein ID-Token behauptet nie einfach "Name = X", sondern "der Issuer Y
  behauptet Name = X, ausgestellt um Zeitpunkt Z". Der Wert ist nie von seiner Quelle getrennt.
- **NIST 800-63 (Digital Identity Guidelines)**: trennt explizit *Identity Proofing* (wie sicher
  ist, wer jemand ist - IAL, Identity Assurance Level) von *Authentication* (wie sicher ist, dass
  derselbe Nutzer wiederkommt - AAL). Unser `maxAcr`/`achievedAcr` auf `ToolDescriptor` deckt
  bereits die zweite Achse ab; die erste Achse - woher stammt eine Identitätsbehauptung, wie sehr
  vertraut man ihr - hat im Datenmodell noch keinen Platz.
- **Verifiable Credentials / W3C VC Data Model**: dieselbe Grundidee nochmal, dort sogar
  kryptografisch: jede Behauptung trägt ihren Aussteller mit.

## Was dadurch gelöst wird

- **Kein Sonderfall mehr für `person_id`/KVNR.** Die Asymmetrie, `ext_stammdaten` als einzige
  mögliche Wahrheitsquelle zu behandeln, verschwindet strukturell statt durch Spezialfall-Code in
  jedem Tool.
- **Ad-hoc-Interessenten passen ohne Schema-Ausnahme.** Ein Konto ohne `ext_stammdaten`-Anker ist
  kein Sonderfall, sondern einfach ein Konto mit weniger Zeilen.
- **Mismatch-Erkennung wird generisch abfragbar**, statt pro Methode neu gebaut: "hat dieser
  Account zwei unterschiedliche Werte für `name` von zwei verschiedenen `trust_anchor`s?" ist dann
  eine Abfrage über `account_attribute`, kein Sonderweg in `ext_stammdaten` oder `id_eid`.
- **Audit für jedes Attribut, nicht nur für den `Identified`-Vorgang.** Heute landet Herkunft nur
  in `ToolOutcome.Completed.Identified.auditDetails` (unstrukturiert, `Map<String, Any?>`,
  Freitext). Mit `account_attribute` ist "wer hat wann was über wen behauptet" eine strukturierte,
  abfragbare Tabelle statt eines Audit-Blobs.

## Der Preis

- **Das ist keine eID-Erweiterung mehr, sondern eine Neufassung des Account-Modells.**
  `account.person_id` wird heute vermutlich an vielen Stellen als gegeben vorausgesetzt:
  `AccountService`/`AccountDirectory` ([tool_api](../03-tool-architektur.md)), `findOrCreateAccount`
  (`FastAccessStrategy`), `DeviceAccountLink.findLinkedAccountId`,
  `ToolOutcome.Completed.Identified(personId: Long, ...)` selbst. Jede dieser Stellen müsste auf
  "ein oder kein `person_id`-Attribut mit Trust-Anchor X" umgestellt werden - auch wenn keine davon
  ihren Lesepfad ändern muss (siehe oben), weil sie weiter aus der Projektion lesen.
- **Migration des Bestands**: jeder existierende `account`-Datensatz müsste seine `person_id` als
  `account_attribute`-Zeile mit `trust_anchor = 'ext_stammdaten'` bekommen, nicht nur neue Konten.
- **Der Schreibpfad wird komplexer**: synchrone Konsolidierung heißt, jedes Schreiben nach
  `account_attribute` löst sofort eine Neuberechnung der Projektion aus, inklusive Konfliktregel -
  bewusst in Kauf genommen, damit die Projektion nie hinter dem Log zurückbleibt.

## Wie man es angehen würde

Nicht in einem Zug. Naheliegende Reihenfolge, falls die Idee weiterverfolgt wird:

1. **`account_attribute` einführen, `account.person_id` vorerst behalten** (beide parallel,
   `person_id` bleibt die "amtliche" schnelle Spalte für alles, was heute schon darauf zugreift).
   Neue Schreibpfade (z. B. `ident-eid`s bestätigte Ausweisdaten) schreiben zusätzlich nach
   `account_attribute` - rein additiv, nichts Bestehendes ändert sich.
2. **Konsolidierungslogik bauen, synchron**: in derselben Transaktion wie das Schreiben nach
   `account_attribute` wird die Projektionstabelle (`account`) neu berechnet - inklusive der
   Konsolidierungsregel oben ("neuester gewinnt"). Ab hier ist `person_id` auf `account` keine
   direkt beschriebene Spalte mehr, sondern ausschließlich das Ergebnis der Konsolidierung.
3. **Lesepfade bleiben unverändert** (`AccountService`, `DeviceAccountLink.findLinkedAccountId`,
   `Identified.personId`, ...) - das ist der eigentliche Gewinn der Projektion: kein Modul, das
   heute `account.person_id` liest, muss angefasst werden.
4. **Erst wenn die Konsolidierung steht**, `person_id NOT NULL` auf `person_id NULLABLE` lockern
   und den ersten personId-losen Kontotyp (Interessent) zulassen - ein Konto ohne
   `ext_stammdaten`-Zeile im Log konsolidiert einfach zu `person_id = NULL`.
5. Die generische `IdentityAnchor`/`resolveIdentity`-Idee aus dem Gespräch (nur KVNR als Anker
   heute, weitere Anker-Arten als eigene `sealed interface`-Fälle bei Bedarf) baut direkt auf
   diesem Modell auf: ein Resolver befragt `account_attribute` nach passenden Kandidaten für einen
   gegebenen Anker-Typ, statt fest an `ext_stammdaten.findPersonIdByKvnr` gebunden zu sein.

## Offene Fragen

- Ist "Interessent" ein eigener `AuthIntent` (eigene Journey, eigene States), oder verzweigt die
  bestehende `REGISTER`-Journey nur je nachdem, ob das Identifizierungsverfahren eine KVNR liefert?
- Braucht `attribute_type` eine geschlossene Liste (Enum, analog zu `FactorType`/`MethodRole` in
  `tool_spi`) oder bleibt es ein offener String? Ein Enum verhindert Tippfehler, macht das Schema
  aber wieder ein Stück weniger generisch - dieselbe Spannung wie bei jeder Claims-Tabelle.
- Der kontoübergreifende `person_id`-Konflikt (zwei Accounts beanspruchen denselben Wert) sollte
  vermutlich *nicht* per "neuester gewinnt" automatisch aufgelöst werden wie ein gewöhnlicher
  Wertekonflikt - was stattdessen passiert, ist noch offen.

## Aktueller Stand

Für `ident-eid` selbst wird diese Idee **nicht** vorausgesetzt - der Mock bekommt vorerst eine
punktuelle Lösung (nullable `person_id`, kleine Seitentabelle für unverknüpfte Identitätsdaten,
falls Interessenten tatsächlich gebraucht werden). Dieses Dokument ist die Gedankenspur, falls das
Account-Modell später grundsätzlich in diese Richtung weiterentwickelt wird.

---

## Anhang: wie wir da hingekommen sind

Der Reihe nach, weil jeder Schritt den vorherigen als zu eng entlarvt hat:

1. **`ident-eid` als Mock gebaut** (wie `ident-fsc`, DPoP-demo-8kr): KVNR/Name/Vorname -> Person in
   `ext_stammdaten` finden, simulierten Kartenleser mit vollen Ausweisdaten (Geburtsdatum,
   Anschrift) drüberlaufen lassen, PIN prüfen. Der Abgleich "stimmen die Ausweisdaten mit dem
   Datensatz überein" landete als `PersonDirectory.matchesStammdaten(personId, ...)` - **innerhalb**
   von `ext_stammdaten`, das dafür alle sieben Felder als Parameter bekam.

2. **Einwand**: `ext_stammdaten` soll nicht selbst *entscheiden*, ob eine Identifizierung
   erfolgreich war - das ist Sache des Orchestrators. `ext_stammdaten` liefert nur Daten (oder,
   ohne sie preiszugeben, ein Ja/Nein zu einer konkreten Behauptung), aber ob ein Mismatch einen
   Vorgang scheitern lässt, ist eine Prozessfrage, keine Stammdatenfrage. Grund: eine echte
   eID-Karte ist ihre eigene vertrauenswürdige Quelle - sie braucht `ext_stammdaten` nicht, um zu
   wissen, wer sie ist.

3. **Generalisiert**: Ein wiederverwendbarer `resolveIdentity(kvnr, claimed)`-Mechanismus, der für
   jedes Identifizierungsverfahren gleich funktioniert (nur die tatsächlich behaupteten Felder
   werden geprüft, `ClaimedIdentity` mit lauter optionalen Feldern), aber die *Bedeutung* eines
   `NotFound`/`Mismatch`-Ergebnisses ist journey-spezifisch (`IntentStrategy.interpret()`, das es
   für genau sowas schon gibt): bei Registrierung ist "nicht gefunden" harmlos (neu anlegen), bei
   Step-Up mitten im Vorgang ist "eine andere Person gefunden" ein harter Fehler.

4. **Einwand**: Selbst "KVNR als Anker" ist eine Festlegung, kein Naturgesetz. Es könnte auch eine
   Telefonnummer sein, oder gar kein Anker - **ad hoc Interessenten**, deren Daten ausschließlich
   aus dem ID-Verfahren selbst stammen (keine KVNR, keine `ext_stammdaten`-Person, existiert nicht,
   bevor sie sich das erste Mal identifiziert haben).

5. **Konkreter Befund**: Das aktuelle Schema trägt das nicht. `account.person_id BIGINT NOT NULL`
   ([V1__schema.sql](../../src/main/resources/db/migration/V1__schema.sql)) verlangt zwingend eine
   `ext_stammdaten`-Person. Ein Interessent ohne KVNR hat schlicht keinen Platz im Modell.

6. **Vorschlag**: identifizierende Attribute nicht als feste Spalten auf `account`, sondern als
   eigene Zeilen, jede mit ihrem eigenen Vertrauensanker.

7. **Verfeinerung**: die klassischen EAV-Nachteile (Typsicherheit, Constraints, Joins) betreffen
   nur den Lesepfad - sie verschwinden, wenn das Attribut-Log nicht die einzige Ablage bleibt,
   sondern zusätzlich synchron in eine typisierte Projektionstabelle konsolidiert wird
   (Event-Sourcing-mit-Read-Model-Muster).

8. **Drei Detailfragen geklärt**: volle Rohdaten bleiben im bestehenden `session_event`-Audit-Trail
   (kein JSON/TEXT in `account_attribute.value`); die Konsolidierung läuft synchron, nicht
   asynchron; Konflikte werden vorerst immer automatisch aufgelöst ("neuester gewinnt") und sind
   allein durch das nie überschriebene Log dokumentiert, statt eine eigene Konflikt-Tabelle zu
   brauchen.
