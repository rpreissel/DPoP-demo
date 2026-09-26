# Idee: Umschlagverschlüsselung für unterschiedliche Aufbewahrung und Widerrufe

> **Status: offen, nicht entschieden** (Issue `DPoP-demo-bo1w`). Ein Vorschlag zur Diskussion, keine Freigabe zur Umsetzung.
> Er betrifft [02-domaenenmodell.md](../02-domaenenmodell.md) Abschnitt 6 (`AccountClaim`,
> `AccountAnchor`, `AccountRetraction`) und die Regeln zur Aufbewahrung in
> [07-betrieb.md](../07-betrieb.md). Im Projekt werden gespeicherte Daten derzeit **nicht**
> verschlüsselt; alle Spalten mit personenbezogenen Daten stehen im Klartext
> (`db/migration/<modul>/`). Der vorhandene kryptografische Code beschränkt sich auf das Hashen von
> Passwörtern (seit 2026-09 Argon2id, vorher PBKDF2), auf HMAC (TAN, E-Mail-Code, Zähler) und auf das Erzeugen von EC-Schlüsseln
> für die Assertions von Keycloak. Eine Regel „eID-Daten höchstens ein Jahr“ steht nirgends in der
> Doku; sie dient hier nur als Beispiel.

## Kontext

Die Frage: Gibt es eine Technik, mit der man je Konto **einen** Schlüssel hat und trotzdem einzelne
Daten mit unterschiedlichen Aufbewahrungsfristen oder Regeln für Widerrufe schützen kann? Konkrete
Beispiele: Die Bestätigung einer E-Mail-Adresse wird zurückgenommen; eID-Daten dürfen höchstens ein
Jahr aufbewahrt werden. Wo und wie der Schlüssel des Kontos selbst aufbewahrt wird, gehört
ausdrücklich **nicht** zur Frage.

**Heutiger Stand (geprüft):**

- Gespeicherte Daten werden derzeit **nicht** verschlüsselt. Alle Spalten mit personenbezogenen
  Daten (`account.claim.claim_value`, `account.anchor.normalized_value`,
  `ext_personenverzeichnis.person.*`, `id_eid.ident_tool_session.*`) sind `VARCHAR` bzw. `DATE` im
  Klartext (`db/migration/<modul>/`). Dazu kommt das simulierte Nect: `nect_mock.ident_case.result`
  hält die ausgelesenen Ausweisdaten eines Vorgangs als JSON im Klartext. Kryptografisch gibt es nur Argon2id (Hash des Passworts, vorher PBKDF2), HMAC
  (TAN, E-Mail-Code, Zähler) und das Erzeugen von EC-Schlüsseln für die Assertions von Keycloak.
  `AccountKeycloakKeypair.privateKeyJwk` ist ausdrücklich als „Demo-only: plaintext, not encrypted at
  rest“ dokumentiert (`orchestrator/kc/AccountKeycloakKeypair.kt:16`).
- Eine Regel „eID-Daten höchstens ein Jahr“ steht nirgends in der Doku; sie ist ein angenommenes
  Beispiel. `id_eid.ident_tool_session` fällt heute unter die allgemeine Frist von 24 Stunden für
  `*_tool_session` ([07-betrieb.md](../07-betrieb.md) Abschnitt 3).
- Ein Widerruf wirkt heute nur logisch: `AccountRetraction` macht Zeilen in `AccountClaim` anhand der
  Zeitpunkte ungültig („Angaben minus Widerrufe“, [02-domaenenmodell.md](../02-domaenenmodell.md)
  Abschnitt 6). Tatsächlich gelöscht wird aber nur der `AccountAnchor` (ADR-12). Die zugehörige Zeile
  in `AccountClaim` bleibt für immer im Klartext liegen; die Frist dafür ist „noch nicht entschieden“
  ([07-betrieb.md](../07-betrieb.md) Abschnitt 3).
- Gelöscht wird heute immer hart, mit SQL-`DELETE` in geplanten `*RetentionJob`s auf Spalten mit
  Index, die den Stichtag enthalten ([07-betrieb.md](../07-betrieb.md) Abschnitt 3). Ein nur
  markierendes Löschen gibt es nirgends.

Ziel dieses Dokuments ist, das Konzept **Umschlagverschlüsselung mit kryptografischem Löschen** zu
erklären, es genau auf das bestehende Modell aus Claims, Ankern und Widerrufen zu übertragen und zwei
konkrete Fälle durchzuspielen.

---

## 1) Die Technik: Umschlagverschlüsselung und kryptografisches Löschen

Die Daten werden nicht direkt mit einem einzigen, langlebigen Schlüssel des Kontos verschlüsselt.
Stattdessen bekommt jede Einheit von Daten ihren **eigenen Datenschlüssel** (Data Encryption Key,
DEK). Dieser Datenschlüssel wird seinerseits mit dem **Hauptschlüssel des Kontos** (Account Master
Key, AMK) verschlüsselt, also „eingepackt“. Einen Hauptschlüssel gibt es genau einmal je Konto. Im
Englischen heißt das Verfahren *Envelope Encryption*.

**Warum ein einziger Schlüssel je Konto nicht reicht:** Ein symmetrischer Schlüssel reicht beim Lesen
genau so weit wie beim Zerstören. Teilen sich der EMAIL-Claim und die eID-Claims denselben Schlüssel,
kann man die verschlüsselte E-Mail-Adresse nicht dauerhaft unlesbar machen, ohne auch die eID-Daten
zu zerstören oder vorher alles andere neu zu verschlüsseln. Dafür müsste man wieder jede andere Zeile
finden und ändern.

Mit einem Datenschlüssel je Einheit löst sich das: Löscht man **einen** kleinen Datenschlüssel (etwa
32 Byte), wird genau der damit verschlüsselte Inhalt dauerhaft unlesbar. Andere Zeilen bleiben
unberührt, nichts muss neu verschlüsselt werden, und es braucht kein `DELETE`, das die eigentliche
(möglicherweise große, indizierte und über Fremdschlüssel verknüpfte) Zeile finden und auch aus
Sicherungen und dem Transaktionslog (WAL) entfernen müsste. Das ist das übliche Muster
„kryptografisches Löschen“ (*Crypto-Shredding* oder *Crypto Erasure*). Es macht aus dem Löschen von
Daten das Löschen eines winzigen Schlüssels.

---

## 2) Wie fein: wofür es je einen Datenschlüssel gibt

Drei Möglichkeiten, bewertet am bestehenden Modell ([02-domaenenmodell.md](../02-domaenenmodell.md)
Abschnitt 6):

- **(a) Ein Datenschlüssel je `AttributeType`** (z. B. ein Schlüssel für alle EMAIL-Claims eines Kontos) — Zu grob. `account.claim` wird nur ergänzt, und ein Konto hat oft mehrere historische Zeilen desselben Typs (alte, korrigierte, zurückgenommene, neu bestätigte E-Mail-Adresse). Ein Widerruf entkräftet nur Claims, die *vor* ihm liegen; ein danach neu bestätigter Wert gilt wieder (ADR-12). Ein gemeinsamer Datenschlüssel würde beim Löschen auch gültige Claims desselben Typs mit zerstören. **Als Hauptweg verworfen.**
- **(b) Ein Datenschlüssel je Zeile in `AccountClaim`** — Passt zur Einheit von `AccountRetraction`, ist aber unnötig fein: Ein Lauf von `ident-eid` schreibt sieben Zeilen (`EID_RESTRICTED_ID`, `NAME`, `VORNAME`, `GEBURTSDATUM`, `STRASSE`, `PLZ`, `ORT`) in einem Aufruf von `recordClaims`. Das wären sieben Datenschlüssel für einen einzigen fachlichen Vorgang. **Zugunsten von (b') verworfen.**
- **(b') Ein Datenschlüssel je Gruppe von Claims** (= eine Transaktion von `recordClaims`) — Ein neues, schmales Feld `claim_batch_id` (UUID), einmal je Aufruf von `recordClaims` erzeugt und an alle Zeilen gehängt, die in diesem Aufruf gespeichert werden. `AccountClaim.authMethodId` eignet sich dafür NICHT: Bei Claims aus einer Identifizierung (`ident-eid`) ist es laut Kommentar im Code immer `null` (`AccountClaim.kt:46-49`, „claims from identification tools produce no credential“). Auch `claimSource` allein reicht nicht: `ClaimSource.of(toolId)` ist für jeden Lauf von `ident-eid` gleich (`"ident-eid"`) und würde eine erneute Identifizierung Jahre später fälschlich mit dem ersten Lauf zusammenwerfen. Ein Datenschlüssel je Gruppe fasst genau zusammen, was fachlich ein Vorgang ist (bei der eID etwa siebenmal weniger Schlüssel), und lässt trotzdem jedem Lesen der Karte seine eigene Frist von einem Jahr. Der Preis: Muss ein einzelnes Attribut einer Gruppe vorzeitig und für sich allein ungültig werden, müssen die übrigen Zeilen der Gruppe neu verschlüsselt werden. Im bestehenden Modell der Widerrufe ist das aber ein Randfall: Das Ersetzen eines Ankers (ADR-12) betrifft nur die lokal verankerten Attribute (`PERSON_ID`, `VERSNR`, `EID_RESTRICTED_ID`, `EMAIL`). Die übrigen eID-Felder gehören dem Personenverzeichnis und stehen nur als Historie im Log; sie werden nicht einzeln widerrufen. **Als Hauptweg für `account.claim` empfohlen.**
- **(c) Ein Datenschlüssel je Aufbewahrungsklasse** (kleines Enum, z. B. `EID_RESTRICTED`, `STANDARD_CLAIM`) — Gröber und billiger zu verwalten. Sobald aber eine Zeile der Klasse vorzeitig gelöscht werden muss, braucht es regelmäßig eine Neuverschlüsselung. **Als pragmatischer Weg für die kurzlebigen Arbeitsdaten der Tools mit personenbezogenen Daten empfohlen** (`id_eid.ident_tool_session` und ähnliche): Dort fällt ohnehin alles innerhalb von etwa 24 Stunden weg, und selbst ein Schlüssel je Gruppe wäre zu viel des Guten.

**Warum an die bestehende Einteilung anknüpfen, statt eine neue zu erfinden:**
[02-domaenenmodell.md](../02-domaenenmodell.md) Abschnitt 6 und ADR-12 haben schon entschieden, was
die Einheit eines Fakts ist. Die Gruppe von Claims ist keine neue Einteilung, sondern macht nur die
Grenze der Transaktion sichtbar, die `recordClaims` ohnehin zieht.

**`account.anchor` bleibt unverschlüsselt, und zwar bewusst, nicht aus Nachlässigkeit** (Abschnitt 3a).

---

## 3a) Suchen: `AccountAnchor` bleibt die unverschlüsselte, abgeleitete Tabelle, die sie schon ist

Umschlagverschlüsselung löst die Vertraulichkeit, nicht die Suche. Verschlüsselte Werte lassen sich
nicht über einen Index auf Gleichheit finden. Normalerweise wäre das ein eigenes Problem (ein
„Blind Index“ mit einem gemeinsamen Pepper). Hier lohnt sich aber zuerst ein Blick darauf, wie die
Aufgaben im Code tatsächlich verteilt sind:

- **`AccountAnchor.resolveByAnchor`** (in `AccountService`, Schnittstelle `AccountDirectory`) liest
  **ausschließlich** über `AccountAnchorRepository`, nie das Claim-Log. `AccountAnchor` ist laut Doku
  schon die abgeleitete Tabelle zum Finden von Konten und zur Sicherung der Eindeutigkeit
  ([02-domaenenmodell.md](../02-domaenenmodell.md) Abschnitt 6), im Aufbau getrennt vom historischen
  Log in `AccountClaim`. Sie enthält nur vier Attributtypen (`PERSON_ID`, `VERSNR`, `EID_RESTRICTED_ID`,
  `EMAIL`): Kennungen und Pseudonyme sowie eine E-Mail-Adresse, aber nicht die eigentlich sensiblen
  Inhalte der eID (Name, Geburtsdatum, Adresse). Diese liegen ausschließlich im Claim-Log.
- **Für `AccountAnchor` ist der Widerruf schon gelöst, ganz ohne Kryptografie:** Nach ADR-12 wird die
  Zeile des Ankers beim Widerruf tatsächlich gelöscht. Das ist stärker als kryptografisches Löschen:
  Es bleibt kein verschlüsselter Inhalt liegen, der mit einem später gestohlenen Schlüssel wieder
  lesbar würde; die Zeile ist einfach weg. Für den Anker war der ganze Aufwand der Verschlüsselung also
  nie nötig. Nötig ist er für das **Claim-Log**, das beim Widerruf gerade *nicht* gelöscht wird
  ([07-betrieb.md](../07-betrieb.md) Abschnitt 3).

**Die Folge:** `AccountAnchor` bleibt im Klartext, ohne Blind Index und ohne zweiten gemeinsamen
Pepper. Die Suche wird damit kein zusätzlicher Baustein, sondern entfällt als Problem. Die bestehende
Trennung trägt die Anforderung schon: der Anker (durchsuchbar, schmal, schon hart löschbar) und das
Claim-Log (historisch, breit, bisher ohne Frist aufbewahrt).

**Offene Frage, aber keine Frage der Kryptografie:** Gilt „eID-Daten höchstens ein Jahr“ auch für die
Zeile des Ankers `EID_RESTRICTED_ID` selbst, also für das Merkmal, an dem ein Interessent
wiedererkannt wird? Falls ja, genügt ein weiterer `*RetentionJob`, der diese Zeile nach einem Jahr
hart löscht. Ein Nutzer, der vor mehr als einem Jahr mit der eID identifiziert wurde, würde dann aber
auf einem neuen Gerät nicht mehr ohne erneute Prüfung der eID wiedererkannt. Das ist eine
Produktentscheidung, die hier nicht getroffen wird.

**Die Suche in `recordClaims`**, die bereits geltende Angaben erkennt (`findEstablished`), ist davon
nicht betroffen: Dort ist das Konto **schon bekannt** (es wird als Parameter übergeben), es gibt also
kein Henne-Ei-Problem. Der Vergleich kann im Anwendungscode geschehen, nachdem die wenigen
bestehenden Claims dieses Kontos entschlüsselt wurden.

---

## 3) Warum es trotzdem „ein Schlüssel je Konto“ ist

Aus Sicht des Kontos gibt es genau **einen** Schlüssel: den Hauptschlüssel des Kontos. Wo und wie er
selbst aufbewahrt wird (KMS, HSM, aus einer Passphrase abgeleitet, nach Shamir aufgeteilt …), ist
bewusst ausgeklammert und als offene Anschlussfrage vermerkt, nicht entworfen (Abschnitt 6).

Der Hauptschlüssel hat nur eine Aufgabe: jeden Datenschlüssel einzupacken. Falls es umgesetzt wird:

- `account.claim` bekommt je Gruppe einen eingepackten Datenschlüssel (über die Tabelle
  `claim_batch_key`, Abschnitt 5). `claim_value` und `normalized_value` werden verschlüsselt
  gespeichert.
- `account.anchor` bleibt unverändert im Klartext (Abschnitt 3a).
- Für den Weg über Aufbewahrungsklassen gibt es eine neue kleine Tabelle
  `account.retention_class_key` (`account_id`, `retention_class`, `wrapped_dek`, `nonce`).

Es gibt also genau ein Geheimnis, das einem Konto gehört (den Hauptschlüssel). Die Schicht der
Datenschlüssel ist ein internes Detail des Verschlüsselungsdienstes. Das Einpacken und Auspacken
sowie das Ver- und Entschlüsseln erledigt reines `javax.crypto` (AES-256-GCM). Das ist dieselbe
Paketfamilie, die das Projekt für PBKDF2 und HMAC schon nutzt; es braucht also keine neue
Abhängigkeit.

---

## 4) Zwei durchgespielte Fälle

**Die Bestätigung einer E-Mail-Adresse wird zurückgenommen.** Heute wird eine `AccountRetraction`
geschrieben und der `AccountAnchor` für EMAIL gelöscht (ADR-12). Die Zeilen in `AccountClaim` für
EMAIL bleiben aber ohne Frist im Klartext liegen ([07-betrieb.md](../07-betrieb.md) Abschnitt 3,
Frist „noch nicht entschieden“). Ein Aufruf von `recordClaims` für EMAIL ist meist eine Gruppe mit
nur einer Zeile; die Einteilung in Gruppen kostet hier also nichts. Mit einem Datenschlüssel je Gruppe
löscht derselbe Widerruf zusätzlich den Datenschlüssel dieser Gruppe, sofort oder nach einer Frist
für Audit und Karenz. Die Zeile selbst kann erhalten bleiben: `attribute_type`, `claim_source`,
`established_acr` und die Zeitstempel bleiben als unverschlüsselte Angaben für das Audit stehen; nur
`claim_value` und `normalized_value` werden dauerhaft unlesbar. Das schließt genau die Lücke, die
ADR-12 offen lässt, ohne dass man auf eine Entscheidung über das massenhafte Löschen von Zeilen
warten muss.

**eID-Daten, höchstens ein Jahr.** Ein Lauf von `ident-eid` schreibt sieben Claims mit gemeinsamer
`claim_batch_id` in einem Aufruf von `recordClaims`. Hier reicht es **nicht**, nur den Datenschlüssel
zu löschen. Was „aktuell gilt“, berechnet das bestehende Modell als „Angaben minus Widerrufe“
([02-domaenenmodell.md](../02-domaenenmodell.md) Abschnitt 6), also allein anhand der Zeilen in
`AccountRetraction`, ganz unabhängig davon, ob sich ein Wert noch lesen lässt. Ohne Widerruf hielte die
Logik, die gültige Werte bestimmt (`findEstablished`, `AccountProfile.establishedClaims`, die Prüfung
auf doppelte Angaben in `recordClaims`), die Claims weiter für gültig, obwohl sie längst unlesbar
sind. Logischer und kryptografischer Zustand würden unbemerkt auseinanderlaufen, und an Stellen, die
von „vorhanden“ ausgehen, könnte das Entschlüsseln fehlschlagen.

Der `RetentionJob` muss deshalb **in einer Transaktion** zwei Dinge tun: (a) für jeden betroffenen
Attributtyp der Gruppe eine Zeile in `AccountRetraction` schreiben und (b) die Zeile in
`claim_batch_key` löschen. Für (a) fehlt derzeit ein passender Wert in `RetractionAnchor`. Die
bestehenden drei (`ACCOUNT_MANAGEMENT`, `PERSON_DIRECTORY`, `OPERATOR`, Letzterer laut Kommentar im
Code ausdrücklich „a human operator, with a reason“) decken einen automatischen Widerruf nach Ablauf
einer Frist nicht ab. Ein vierter Wert (`RETENTION_POLICY`) wäre nötig, damit ein geplanter Job bei
„wer widerruft“ nicht fälschlich als Eingriff eines Menschen erscheint.

Eine spätere erneute Identifizierung (neue Karte, neue `claim_batch_id`) bekommt ihre eigene,
unabhängige Frist. Im Kern ist das derselbe Aufbau, der schon im Betrieb läuft (`*RetentionJob`,
[07-betrieb.md](../07-betrieb.md) Abschnitt 3). Neu ist nur, dass der Job hier auch die Widerrufe
einträgt, die bei einem vom Nutzer ausgelösten Widerruf (Beispiel E-Mail oben) der auslösende Vorgang
selbst liefert.

---

## 5) Was neu wäre und was bleibt

**Neu** (falls es umgesetzt wird):

- Ein neues Feld `claim_batch_id` (UUID; für alte Daten darf es leer sein) in `account.claim`,
  einmal je Aufruf von `recordClaims` erzeugt.
- Eine Tabelle `account.claim_batch_key` (`account_id`, `claim_batch_id`, `wrapped_dek`, `nonce`)
  statt zweier zusätzlicher Spalten direkt in `account.claim`. Das passt besser zur 1:n-Beziehung
  zwischen Gruppe und Zeilen.
- Ein kleiner `ClaimCryptoService` im Modul `account` (`javax.crypto.Cipher`, AES-256-GCM), nach dem
  Vorbild von `PasswordHasher.kt`.
- Für die Arbeitsdaten der Tools: eine Tabelle `account.retention_class_key` und das Nachschlagen
  darin in den betroffenen Modulen.
- Ein neuer Wert `RetractionAnchor.RETENTION_POLICY`, damit ein automatischer Widerruf nach Ablauf
  einer Frist nicht fälschlich als Eingriff eines Menschen (`OPERATOR`) erscheint (Abschnitt 4).

**Unverändert übernommen:**

- Ein eigenes Schema je Modul (alles bleibt im Schema `account`).
- Das bestehende Muster der `*RetentionJob`s: Das Löschen von Datenschlüsseln ist nur ein weiterer
  Job, der nach einem Stichtag arbeitet.
- Die Trennung zwischen `AccountClaim` (wird nur ergänzt) und `AccountAnchor` (abgeleitete Tabelle)
  sowie die Einheit von `AccountRetraction` (ADR-12). Der Datenschlüssel setzt genau auf der schon
  vorhandenen Zeile auf.

---

## 6) Ausdrücklich ausgeklammert

Wo und wie der **Hauptschlüssel selbst** aufbewahrt, geschützt und regelmäßig erneuert wird (KMS,
HSM, ein Geheimnis im Anwendungsprozess, nach Shamir aufgeteilt …), ist eine eigene, bewusst
ausgeklammerte Anschlussfrage und nicht Teil dieses Vorschlags. Sie ist aber praktisch wichtig: Ein
Hauptschlüssel im Klartext in der Konfiguration der Anwendung wäre kaum besser als der heutige
Zustand; vergleiche den offenen Hinweis bei `AccountKeycloakKeypair.privateKeyJwk` („Demo-only:
plaintext, not encrypted at rest“).

---

## 7) Abwägungen, offen gesagt

- **Kryptografisches Löschen ist nicht von selbst ein Löschen im Sinne der DSGVO.** Enthalten
  Sicherungen, Transaktionslog oder Replikate den verschlüsselten Inhalt *und* den Datenschlüssel
  zusammen (etwa eine nächtliche Sicherung von vor dem Löschen des Schlüssels oder ein Replikat, das
  hinterherhinkt), lässt sich der „gelöschte“ Wert wiederherstellen, bis diese Kopie verfallen ist.
  Die Sicherungen der Tabelle mit den Datenschlüsseln müssen deshalb mindestens so kurz aufbewahrt
  werden wie die der Tabelle mit den Daten. Das ist eine echte Abhängigkeit im Betrieb, kein Detail.
- **Rechenaufwand:** Bei jedem Zugriff auf einen Claim wird einmal ver- oder entschlüsselt.
  AES-GCM ist schnell; im Umfang der Demo fällt das nicht ins Gewicht.
- **Eine neue Stelle, an der alles hängt:** Geht die Tabelle mit den Datenschlüsseln verloren
  (beschädigt, versehentlich massenhaft gelöscht, unvollständig wiederhergestellt), werden auf einen
  Schlag *alle* Claims *aller* Konten unlesbar. Das ist ein härterer Fehlerfall als der heutige
  Klartext. Diese Tabelle braucht eine eigene, entsprechend strenge Sicherung.
- **Rahmen der Demo:** Bewusst wird keine Anbindung an ein KMS vorgeschlagen, sondern die kleinste
  umsetzbare Variante (`javax.crypto`, keine neue Abhängigkeit, das Muster der `RetentionJob`s
  wiederverwendet). Für einen Produktivbetrieb müsste die Frage nach der Aufbewahrung des
  Hauptschlüssels (Abschnitt 6) vor dem ersten echten Einsatz geklärt sein.

---

## 8) Mengen im Produktivbetrieb

Eine grobe Schätzung, keine belastbare Planung der Kapazität, veranschaulicht an der Größe einer
großen gesetzlichen Krankenkasse (etwa 11 Millionen Versicherte).

- **Wachstum der Schlüsseltabelle:** Etwa 3 bis 5 Gruppen von Claims je Konto über dessen Lebensdauer
  ergeben **30 bis 55 Millionen Zeilen** in `claim_batch_key` (6 bis 10 GB bei etwa 150 bis 200 Byte
  je Zeile). Das ist für sich genommen überschaubar, aber es ist eine neue Tabelle, die bei jedem
  Zugriff auf einen Claim mitgelesen wird.
- **Der eigentliche Engpass ist nicht das Verschlüsseln, sondern das Auspacken mit dem
  Hauptschlüssel.** AES-256-GCM fällt bei jeder realistischen Last nicht ins Gewicht. Liegt der
  Hauptschlüssel aber hinter einem KMS oder HSM, bedeutet ein einfaches „Hauptschlüssel bei jedem
  Zugriff auf einen Claim entschlüsseln“ jedes Mal einen Weg über das Netz (etwa 10 bis 20 ms), dazu
  die Grenzen des KMS für Anfragen je Sekunde (bei AWS KMS etwa 5.500 bis 10.000 je Schlüssel) und
  Kosten je Aufruf. Bei Millionen Anmeldungen am Tag könnte das der größte Kostenblock werden.
  **Abhilfe:** Den Hauptschlüssel einmal je Sitzung oder Anfrage entschlüsseln und im Arbeitsspeicher
  vorhalten (nie gespeichert, kurze Lebensdauer). Die Last auf dem KMS wächst dann mit der Zahl der
  Sitzungen und nicht mit der Zahl der Zugriffe auf Claims.
- **Viele einzelne Schlüssel beim Lesen der ganzen Historie** (K Gruppen ergeben K Auspackvorgänge,
  etwa für eine Auskunft nach Art. 15 DSGVO) sind unkritisch, weil es nur um ein Konto geht und K klein
  ist. Im Normalfall (aktueller Wert) wird ohnehin `AccountAnchor` gelesen (Abschnitt 3a), nicht das
  Claim-Log. Das teure Lesen vieler Gruppen ist die Ausnahme und liegt nicht auf dem Weg der Anmeldung.
- **`RetentionJob` darf das wachsende Claim-Log nicht per Join durchsuchen.** `expires_at` muss **schon
  beim Schreiben** in `recordClaims` direkt in `claim_batch_key` gespeichert werden; die Attributtypen
  der Gruppe und ihre Aufbewahrungsregel sind zu diesem Zeitpunkt bekannt. Der Job bleibt dann ein
  einfaches `DELETE ... WHERE expires_at < now()` über einen Index, wie beim bestehenden Muster.
  Daraus folgt eine Regel, die erzwungen werden muss: Eine Gruppe darf nur Attributtypen mit
  **derselben** Aufbewahrungsregel enthalten, sonst setzt sich beim Löschen die längste Frist durch.
- **Bei Produktivmengen wird der mögliche Schaden greifbar.** Geht die Schlüsseltabelle mit 30 bis
  55 Millionen Zeilen verloren, trifft das nicht ein Konto, sondern alle zugleich. Das ist eher ein
  Totalausfall als der Verlust einer gewöhnlichen Tabelle. Die Tabelle braucht eigene, strengere
  Zusagen für Verfügbarkeit und Wiederherstellung (fast kein Datenverlust erlaubt) und eher einen
  eigenen, überwachten Datenspeicher als „noch eine Tabelle im Schema `account`“.
- **Ein echter Vorteil bei großen Mengen: Den Hauptschlüssel zu erneuern wird billig.** Dabei ändert
  sich nur die kleine Schlüsseltabelle (die Datenschlüssel werden mit dem neuen Hauptschlüssel neu
  eingepackt), nicht die 30 bis 55 Millionen Zeilen in `AccountClaim` mit personenbezogenen Daten. Mit
  einem einzigen Schlüssel ohne Datenschlüssel müsste man beim Erneuern alle Daten neu verschlüsseln,
  bei Produktivmengen ein Vorhaben über mehrere Tage. Hier lohnt sich die Schicht der Datenschlüssel
  bei echten Mengen also nicht nur in der Theorie.

---

## Betroffene Dateien (nur als Hinweis für eine spätere Umsetzung)

- `src/main/kotlin/com/example/dpop/account/internal/AccountClaim.kt` (neues Feld `claim_batch_id`)
- `src/main/kotlin/com/example/dpop/account/AccountService.kt` (`recordClaims`: je Aufruf eine
  `claim_batch_id` erzeugen)
- `src/main/kotlin/com/example/dpop/account/internal/AccountAnchor.kt` (unverändert; Bezugspunkt für
  die Abgrenzung in Abschnitt 3a)
- `src/main/kotlin/com/example/dpop/account/internal/AccountRetraction.kt`
- `docs/12-entscheidungen.md` (neues ADR für diese Entscheidung)
- `docs/07-betrieb.md` (bestehendes Muster der `*RetentionJob`s erweitern)

## Nächster Schritt

Wird das Vorhaben weiterverfolgt, sind diese Anschlussfragen offen: (1) die Aufbewahrung des
Hauptschlüssels (Abschnitt 6), (2) ob es Datenschlüssel je Gruppe für *alle* Attributtypen gibt oder
nur für die sensiblen (`EID_*`, `EMAIL`), (3) wie bestehende Claims im Klartext umgestellt werden,
(4) ob die Frist von einem Jahr für eID-Daten auch die Zeile des Ankers `EID_RESTRICTED_ID` treffen
soll, mit den Folgen für das Wiedererkennen (Abschnitt 3a), und (5) die Einführung von
`RetractionAnchor.RETENTION_POLICY` für automatische Widerrufe nach Ablauf einer Frist (Abschnitt 4).
