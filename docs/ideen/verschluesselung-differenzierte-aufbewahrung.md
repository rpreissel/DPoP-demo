# Idee: Envelope Encryption für differenzierte Aufbewahrung/Retraction

> **Status: offen, nicht entschieden.** Diskussionsvorschlag, keine Implementierungsfreigabe.
> Betrifft [02-domaenenmodell.md](../02-domaenenmodell.md) §6 (`AccountClaim`/`AccountAnchor`/
> `AccountRetraction`) und die Aufbewahrungsregeln in [07-betrieb.md](../07-betrieb.md). Im
> Projekt existiert aktuell **keine** Verschlüsselung ruhender Daten — alle PII-Spalten sind
> Klartext (`db/migration/<modul>/`); vorhandener Krypto-Code beschränkt sich auf PBKDF2-Passwort-Hashing,
> HMAC (TAN/E-Mail-Code, Throttle) und EC-Keygen für Keycloak-Assertions. Eine „eID max. 1 Jahr"-
> Regel existiert nirgends in der Doku — sie dient hier nur als illustratives Beispiel.

## Kontext

Frage: Gibt es eine Technik, mit der man pro Account **einen** Schlüssel hat, aber trotzdem
einzelne Datenfelder mit unterschiedlichen Aufbewahrungsfristen oder Retraction-Policies absichern
kann? Konkrete Beispiele: E-Mail-Bestätigung wird zurückgezogen; eID-Daten dürfen maximal ein Jahr
aufbewahrt werden. Schlüsselaufbewahrung (wo/wie der Account-Schlüssel selbst liegt) ist
ausdrücklich **nicht** Teil der Frage.

**Ist-Zustand (verifiziert):**
- Im Projekt existiert aktuell **keine** Verschlüsselung ruhender Daten. Alle PII-Spalten
  (`account.claim.claim_value`, `account.anchor.normalized_value`,
  `ext_personenverzeichnis.person.*`, `id_eid.ident_tool_session.*`) sind Klartext-`VARCHAR`/`DATE`
  (`db/migration/<modul>/`). Vorhandener Krypto-Code ist nur PBKDF2 (Passwort-Hash), HMAC (TAN/E-Mail-Code,
  Throttle) und EC-Keygen für Keycloak-Assertions — `AccountKeycloakKeypair.privateKeyJwk` ist
  explizit als „Demo-only: plaintext, not encrypted at rest" dokumentiert
  (`AccountKeycloakKeypair.kt:16-17`).
- Eine „eID max. 1 Jahr"-Regel existiert nirgends in der Doku — das ist ein hypothetisches
  Beispiel. `id_eid.ident_tool_session` fällt heute unter die generische 24h-Regel für
  `*_tool_session` (`07-betrieb.md:42`).
- Retraction funktioniert heute rein logisch: `AccountRetraction` invalidiert `AccountClaim`-Zeilen
  zeitbasiert („Angaben minus Widerrufe", `02-domaenenmodell.md:142`), löscht aber nur den
  `AccountAnchor` physisch (ADR-12). Die zugehörige `AccountClaim`-Zeile bleibt für immer im
  Klartext liegen — Löschfrist dafür „noch nicht entschieden" (`07-betrieb.md:52`).
- Alle Löschung heute: harte SQL-`DELETE`s über geplante `*RetentionJob`s auf indizierten
  Cutoff-Spalten (`07-betrieb.md:40-65`), kein Soft-Delete/Tombstone irgendwo.

Ziel dieses Dokuments: das Konzept **Envelope Encryption mit Crypto-Shredding** erklären, es genau
auf das bestehende Claim/Anchor/Retraction-Modell übertragen und zwei konkrete Szenarien
durchspielen.

---

## 1) Die Technik: Envelope Encryption + Crypto-Shredding

Statt Daten direkt mit einem einzigen, langlebigen Account-Schlüssel zu verschlüsseln, bekommt
jede Einheit an Daten ihren **eigenen Data Encryption Key (DEK)**. Dieser DEK wird seinerseits vom
**Account Master Key (AMK)** — genau ein Schlüssel pro Account — verschlüsselt („gewrapped").

**Warum ein einziger Account-Schlüssel allein nicht reicht:** Ein symmetrischer Schlüssel reicht
immer genau so weit, beim Lesen wie beim Zerstören. Teilen sich EMAIL-Claim und
EID-Claims denselben Schlüssel, kann man das EMAIL-Chiffrat nicht dauerhaft unlesbar machen, ohne
entweder auch die EID-Daten zu zerstören oder vorher den Rest umzuschlüsseln — was wieder bedeutet,
jede andere Zeile finden und anfassen zu müssen.

Mit einem DEK pro Dateneinheit löst sich das: Das Löschen **eines** kleinen (~32 Byte) DEK macht
genau dessen Chiffrat dauerhaft unlesbar — ohne andere Zeilen anzufassen, ohne Umschlüsselung, ohne
ein `DELETE`, das die eigentliche (ggf. große, indizierte, FK-referenzierte) Nutzdatenzeile finden
und aus Backups/WAL entfernen müsste. Das ist das Standardmuster „Crypto-Shredding"/„Crypto
Erasure" — es macht aus einem Datenlöschproblem ein Problem des Löschens eines winzigen Schlüssels.

---

## 2) Granularität: wohin die DEKs mappen

Drei Kandidaten, bewertet gegen das bestehende Modell (`02-domaenenmodell.md` §6):

| Granularität | Bewertung |
|---|---|
| **(a) Ein DEK pro `AttributeType`** (z. B. ein Schlüssel für alle EMAIL-Claims eines Accounts) | Zu grob: an `account.claim` wird nur angefügt, ein Account hat oft mehrere historische Zeilen desselben Typs (alte/korrigierte/zurückgezogene/neu bestätigte E-Mail). Ein Widerruf entkräftet nur Claims *vor* ihm — ein danach neu bestätigter Wert zählt wieder (ADR-12). Ein gemeinsamer DEK würde beim Shredden auch aktuell gültige Claims desselben Typs mit zerstören. **Verworfen als primärer Mechanismus.** |
| **(b) Ein DEK pro `AccountClaim`-Zeile** | Trifft die Granularität von `AccountRetraction`, ist aber unnötig fein: ein eID-Scan schreibt 8 Zeilen (`EID_RESTRICTED_ID`, `NAME`, `VORNAME`, `GEBURTSDATUM`, `STRASSE`, `HAUSNUMMER`, `PLZ`, `ORT`) in einem `recordClaims`-Aufruf (`AccountService.kt:131-183`) — 8 DEKs für einen fachlichen Vorgang. **Verworfen zugunsten von (b').** |
| **(b') Ein DEK pro Claim-Batch** (= eine `recordClaims`-Transaktion) | Neues, schmales Feld `claim_batch_id` (UUID), einmal pro `recordClaims`-Aufruf erzeugt und an alle in diesem Aufruf gespeicherten Zeilen gehängt. `AccountClaim.authMethodId` taugt dafür NICHT als Gruppierungsschlüssel — für Identifizierungs-Claims (`ident-eid`) ist es laut Code-Kommentar immer `null` (`AccountClaim.kt:46-49`, „claims from identification tools produce no credential"). Auch `claimSource` allein reicht nicht: `ClaimSource.of(toolId)` = `"ident-eid"` ist für jeden eID-Lauf gleich und würde eine Neuidentifizierung Jahre später fälschlich mit dem ersten Lauf verschmelzen. Ein Batch-DEK bündelt genau das, was fachlich als ein Vorgang zusammengehört (~8x weniger Schlüssel für eID) und lässt trotzdem jeden Scan seine eigene 1-Jahres-Uhr behalten. Kosten: Muss ein einzelnes Attribut innerhalb eines Batches vorzeitig unabhängig sterben, braucht das ein Re-Key der übrigen Zeilen des Batches — im bestehenden Retraction-Modell aber ein Randfall: der Anker-Ersatz-Trigger (ADR-12) trifft nur die drei lokal verankerten Attribute (PERSON_ID/EID_RESTRICTED_ID/EMAIL), die übrigen eID-Felder sind `PERSON_DIRECTORY`-Autorität und laufen nur als Konsistenz-Log, nicht einzeln retrahiert. **Empfohlen als primärer Mechanismus für `account.claim`.** |
| **(c) Ein DEK pro Retention-Klasse** (kleines Enum, z. B. `EID_RESTRICTED`, `STANDARD_CLAIM`) | Grober und billiger zu verwalten, aber periodisches Re-Keying nötig, sobald eine Zeile der Klasse vorzeitig sterben muss. **Empfohlen als pragmatischer Mechanismus für die kurzlebigen Tool-Session-PII-Tabellen** (`id_eid.ident_tool_session` u. ä.), wo ohnehin alles binnen ~24h fällt und selbst ein Batch-DEK Overkill wäre. |

**Warum an bestehender Granularität andocken statt neue Taxonomie erfinden:** `02-domaenenmodell.md`
§6 und ADR-12 haben die Entscheidung „was ist die Einheit eines Fakts" bereits getroffen. Der
Claim-Batch ist keine neue Taxonomie, sondern nur die explizit gemachte Transaktionsgrenze, die
`recordClaims` ohnehin schon zieht.

**`account.anchor` bleibt unverschlüsselt — bewusst, nicht aus Nachlässigkeit** (Abschnitt 3a).

---

## 3a) Suchbarkeit: `AccountAnchor` bleibt die unverschlüsselte Projektion, die sie schon ist

Envelope Encryption löst Vertraulichkeit, nicht Suchbarkeit — verschlüsselte Werte lassen sich
nicht per Gleichheits-Index finden. Das wäre normalerweise ein eigenes Problem (Blind Index mit
globalem Pepper), aber hier lohnt sich der Blick auf die tatsächliche Aufgabenteilung im Code
zuerst:

- **`AccountAnchor.resolveByAnchor`** (`AccountService.kt:504`, Interface `AccountDirectory.kt:23`)
  liest **ausschließlich** `AccountAnchorRepository` — nie den Claim-Log. `AccountAnchor` ist laut
  Doku bereits „die Auflösungs- und Eindeutigkeits-**Projektion**" (`02-domaenenmodell.md:147`),
  strukturell getrennt vom historischen `AccountClaim`-Log. Sie trägt nur 3 Attributtypen
  (`PERSON_ID`, `EID_RESTRICTED_ID`, `EMAIL`) — Referenzwerte/Pseudonyme plus eine E-Mail-Adresse,
  nicht die eigentlich sensiblen eID-Inhalte (Name, Geburtsdatum, Adresse), die ausschließlich im
  Claim-Log liegen.
- **Der Widerruf ist für `AccountAnchor` bereits gelöst — ohne Krypto:** ADR-12 löscht die
  Anker-Zeile beim Widerruf physisch per `DELETE`. Das ist stärker als Crypto-Shredding (kein
  Chiffrat bleibt liegen, das mit einem später kompromittierten Schlüssel wieder lesbar würde — die
  Zeile ist schlicht weg). Der ganze Verschlüsselungsaufwand ist für den Anker nie nötig gewesen;
  nötig ist er für den **Claim-Log**, der laut `07-betrieb.md:52` beim Widerruf gerade *nicht*
  gelöscht wird.

**Konsequenz:** `AccountAnchor` bleibt Klartext, kein Blind Index, kein zweiter globaler Pepper.
Suchbarkeit ist damit kein zusätzlicher Baustein, sondern entfällt — die bestehende Trennung
Anker (suchbar, schmal, schon hart löschbar) vs. Claim-Log (historisch, breit, bisher unbegrenzt
aufbewahrt) trägt die Anforderung bereits.

**Offener Punkt, keine Krypto-Frage:** Gilt „eID max. 1 Jahr" auch für die `EID_RESTRICTED_ID`-
Anker-Zeile selbst — den „Wiedererkennungsanker" für einen Interessenten (`Claims.kt:18-23`)? Falls
ja, reicht ein weiterer `*RetentionJob`, der diese Anker-Zeile nach 1 Jahr hart löscht — aber ein
Nutzer, der vor über einem Jahr per eID identifiziert wurde, würde dann auf einem neuen Gerät nicht
mehr wiedererkannt, ohne erneute eID-Prüfung. Eine Produktentscheidung, hier nicht getroffen.

**`AccountClaim`s eigener Index** (`ix(attribute_type, normalized_value, account_id)`, genutzt in
`recordClaims`' Dedup-Check `findEstablished`) betrifft das nicht: hier ist der Account **schon
bekannt** (Methodenparameter) — kein Henne-Ei-Problem. Vergleich kann nach Entschlüsselung der
wenigen bestehenden Claims dieses Kontos in Anwendungscode passieren.

---

## 3) Wie „ein Schlüssel pro Account" trotzdem stimmt

Aus Account-Sicht gibt es genau **einen** Schlüssel: den Account Master Key (AMK). Wo/wie der AMK
selbst verwahrt wird (KMS, HSM, passphrase-abgeleitet, Shamir-Split …), ist bewusst ausgeklammert —
als offene Folgefrage markiert, nicht entworfen (Abschnitt 6).

Der AMK hat nur eine Aufgabe: jeden DEK zu wrappen. Falls/wenn umgesetzt:

- `account.claim` bekommt (über die `claim_batch_key`-Tabelle, Abschnitt 5) einen wrapped DEK pro
  Batch. `claim_value`/`normalized_value` werden zu Chiffrat.
- `account.anchor` bleibt unverändert Klartext (Abschnitt 3a).
- Für den Retention-Klassen-Ansatz: eine neue kleine Tabelle
  `account.retention_class_key` (`account_id`, `retention_class`, `wrapped_dek`, `nonce`).

Es gibt also genau ein Geheimnis, das ein Account „besitzt" (den AMK); die DEK-Schicht ist ein
internes Implementierungsdetail des Verschlüsselungsdienstes. Wrap/Unwrap und
Encrypt/Decrypt sind reines `javax.crypto` (AES-256-GCM) — dieselbe Paketfamilie, die das Projekt
für PBKDF2/HMAC schon nutzt, also keine neue Abhängigkeit.

---

## 4) Durchgespielte Szenarien

**E-Mail-Bestätigung wird zurückgezogen.** Heute: `AccountRetraction` wird geschrieben, der
`AccountAnchor` für EMAIL physisch gelöscht (ADR-12), aber die `AccountClaim`-Zeile(n) für EMAIL
bleiben unbegrenzt im Klartext liegen (`07-betrieb.md:52`, Frist „noch nicht entschieden"). Ein
EMAIL-`recordClaims`-Aufruf ist typischerweise ein Batch mit nur einer Zeile — die
Batch-Granularität kostet hier nichts. Mit Claim-Batch-DEKs: derselbe Widerruf löscht zusätzlich
den DEK dieses Batches (sofort oder nach einer Karenz-/Audit-Frist). Die Zeile selbst kann
strukturell erhalten bleiben — `attribute_type`, `claim_source`, `established_acr`, Zeitstempel
bleiben unverschlüsselte Metadaten für Audit-Zwecke — nur `claim_value`/`normalized_value` werden
dauerhaft unlesbares Chiffrat. Das schließt genau die Lücke, die ADR-12 offen lässt, ohne auf eine
Entscheidung zur physischen Massenlöschung warten zu müssen.

**eID-Daten, max. 1 Jahr.** Ein `ident-eid`-Lauf schreibt 8 Claims mit gemeinsamer `claim_batch_id`
in einem `recordClaims`-Aufruf. Reines DEK-Löschen reicht hier **nicht**: „Aktuell gültig" wird im
bestehenden Modell als „Angaben minus Widerrufe" berechnet (`02-domaenenmodell.md:142`) —
rein über `AccountRetraction`-Zeilen, unabhängig von Lesbarkeit. Ohne Retraction hielte die
Konsolidierungslogik (`findEstablished`, `AccountProfile.establishedClaims`, der Dedup-Check in
`recordClaims`) die Claims weiterhin für gültig, während sie tatsächlich unlesbares Chiffrat sind —
ein unbemerkter Widerspruch zwischen Logik- und Krypto-Zustand, potenziell ein Entschlüsselungsfehler
an Stellen, die von „vorhanden" ausgehen.

Der `RetentionJob` muss deshalb **in einer Transaktion** zwei Dinge tun: (a) für jeden betroffenen
Attributtyp des Batches eine `AccountRetraction`-Zeile schreiben, (b) die `claim_batch_key`-Zeile
löschen. Für (a) fehlt aktuell ein passender `RetractionAnchor`-Fall: die bestehenden drei
(`ACCOUNT_MANAGEMENT`, `PERSON_DIRECTORY`, `OPERATOR` — Letzterer laut Code-Kommentar explizit „a
human operator, with a reason") decken einen automatisch fristbasierten Widerruf nicht ab; ein
vierter Fall (`RETENTION_POLICY`) wäre nötig, damit „wer widerruft" für einen Scheduled Job nicht
fälschlich als menschliche Operator-Aktion erscheint.

Eine spätere Neuidentifizierung (neue Karte, neue `claim_batch_id`) bekommt ihre eigene,
unabhängige Frist. Im Kern strukturell dasselbe Muster, das schon produktiv läuft (`*RetentionJob`,
`07-betrieb.md:40-55`) — nur dass der Job hier zusätzlich die Retraction-Buchhaltung übernimmt, die
bei einem nutzerausgelösten Widerruf (E-Mail-Beispiel oben) der auslösende Vorgang selbst liefert.

---

## 5) Neu vs. wiederverwendet

**Neu** (falls/wenn umgesetzt):
- Neues Feld `claim_batch_id` (UUID, nullable erlaubt für Altdaten) auf `account.claim`, einmal
  pro `recordClaims`-Aufruf erzeugt.
- Eine `account.claim_batch_key`-Tabelle (`account_id`, `claim_batch_id`, `wrapped_dek`, `nonce`)
  statt eines Spaltenpaars direkt auf `account.claim` — passt besser zur 1:n-Beziehung
  Batch→Zeilen.
- Ein kleiner `ClaimCryptoService` im `account`-Modul (`javax.crypto.Cipher`, AES-256-GCM),
  analog zu `PasswordHasher.kt`.
- Für den Tool-Session-Pfad: `account.retention_class_key`-Tabelle plus Lookup in den
  betroffenen Modulen.
- Neuer `RetractionAnchor.RETENTION_POLICY`-Fall, damit ein fristbasierter, automatischer Widerruf
  nicht fälschlich als menschliche `OPERATOR`-Aktion erscheint (Abschnitt 4).

**Wiederverwendet, unverändert:**
- Eigenes Schema pro Modul (bleibt im `account`-Schema).
- Das bestehende `*RetentionJob`-Muster — DEK-Löschung ist nur ein weiterer cutoff-getriebener Job.
- Die Trennung zwischen `AccountClaim` (nur anfügen) und `AccountAnchor` (Projektion) und die
  `AccountRetraction`-Granularität (ADR-12) — der DEK setzt exakt auf die schon vorhandene Zeile auf.

---

## 6) Ausdrücklich außen vor gelassen

Wo und wie der **AMK selbst** verwahrt, geschützt und rotiert wird (KMS, HSM, ein vom
Anwendungsprozess gehaltenes Master-Secret, Shamir-Split …) ist eine separate, bewusst
ausgeklammerte Folgefrage — nicht Teil dieses Vorschlags. Sie ist praktisch relevant: ein AMK im
Klartext in der Anwendungskonfiguration wäre kaum besser als der heutige Zustand, vgl. die ehrliche
Anmerkung bei `AccountKeycloakKeypair.privateKeyJwk` („Demo-only: plaintext, not encrypted at
rest").

---

## 7) Trade-offs / Ehrlichkeit

- **Crypto-Shredding ist nicht automatisch DSGVO-konforme Löschung.** Wenn Backups/WAL/Replikate
  Chiffrat *und* DEK gemeinsam einfangen (z. B. ein nächtlicher Dump vor dem DEK-Delete, ein
  nachhinkender Replica), ist der „gelöschte" Wert bis zum Altern dieser Kopie wiederherstellbar.
  Die Backup-Politik für die DEK-Tabelle muss mindestens so aggressiv sein wie die für die
  Nutzdatentabelle — eine echte betriebliche Abhängigkeit, kein Detail.
- **Performance-Kosten**: ein Encrypt/Decrypt pro Claim-Zugriff (AES-GCM ist schnell, bei
  Demo-Umfang vernachlässigbar).
- **Neuer Single Point of Failure**: Geht die DEK-Tabelle verloren (Korruption, versehentliches
  Bulk-Delete, unvollständiges Backup-Restore), werden schlagartig *alle* Claims *aller* Accounts
  unlesbar — ein härteres Fehlerbild als der heutige Klartext-Zustand. Die DEK-Tabelle braucht eine
  eigene, entsprechend strenge Backup-Disziplin.
- **Demo-Rahmen**: Bewusst keine KMS-Integration vorgeschlagen — kleinstmögliche umsetzbare
  Variante (`javax.crypto`, keine neue Abhängigkeit, Wiederverwendung des `RetentionJob`-Musters).
  Für einen Produktivbetrieb wäre die AMK-Verwahrungsfrage (Abschnitt 6) vor einer echten Nutzung
  zwingend zu klären.

---

## 8) Produktionsvolumen

Grobe Schätzung, keine belastbare Kapazitätsplanung — illustriert an der Größenordnung eines
großen gesetzlichen Krankenversicherers (~11 Mio. Versicherte).

- **Key-Tabellen-Wachstum:** ~3–5 Claim-Batches/Account über die Kontolebensdauer ⇒
  **30–55 Mio. Zeilen** in `claim_batch_key` (6–10 GB bei ~150–200 Byte/Zeile). An sich moderat,
  aber eine neue, bei jedem Claim-Zugriff mitgelesene Tabelle.
- **Eigentlicher Engpass ist nicht die Verschlüsselung, sondern der AMK-Unwrap.** AES-256-GCM ist
  bei jeder realistischen Last vernachlässigbar. Liegt der AMK hinter einem KMS/HSM, bedeutet ein
  naives „AMK pro Claim-Zugriff entschlüsseln" einen Netzwerk-Roundtrip (~10–20ms) plus
  KMS-Ratenlimits (z. B. AWS KMS default ~5.500–10.000 Req/s je Schlüssel) plus Kosten pro Call —
  bei Millionen Logins/Tag potenziell das dominierende Kostenzentrum. **Mitigation:** AMK einmal
  pro Session/Request entschlüsseln und im Prozessspeicher cachen (nie persistiert, kurze TTL) —
  KMS-Traffic skaliert dann mit Session-Zahl, nicht mit Claim-Zugriffszahl.
- **N+1 beim Lesen** (K Batches ⇒ K DEK-Unwraps für die volle Historie, z. B.
  DSGVO-Art.-15-Auskunft) ist unkritisch: kontoscoped, K klein. Der Regelfall (aktueller Wert)
  liest ohnehin `AccountAnchor` (Abschnitt 3a), nicht den Claim-Log — die teuren Multi-Batch-Reads
  sind die Ausnahme, nicht der Login-Pfad.
- **`RetentionJob` darf nicht gegen den wachsenden Claim-Log joinen.** `expires_at` muss **zur
  Schreibzeit** in `recordClaims` direkt auf `claim_batch_key` gespeichert werden (die
  Attributtypen des Batches und ihre Retention-Regel sind zu dem Zeitpunkt bekannt) — der Job
  bleibt dann ein simples indiziertes `DELETE ... WHERE expires_at < now()`, das bestehende
  Muster. Daraus folgt eine zu erzwingende Invariante: Ein Batch darf nur Attributtypen **derselben**
  Retention-Policy bündeln, sonst gewinnt beim Löschen die längste Frist.
- **Der Schaden wird bei Produktionsmengen konkret, nicht nur theoretisch.** Verlust der Key-Tabelle
  trifft bei 30–55 Mio. Zeilen nicht ein Konto, sondern alle gleichzeitig — eher ein
  Totalausfall-Szenario als ein gewöhnlicher Tabellenverlust. Braucht eigene, strengere SLOs
  (RPO nahe 0), eher einen eigenen überwachten Datastore als „eine weitere Tabelle im
  `account`-Schema".
- **Echter Vorteil bei Prod-Volumen: AMK-Rotation wird billig.** Rotation berührt nur die kleine
  Key-Tabelle (AMK neu wrappen), nicht die 30–55 Mio. PII-tragenden `AccountClaim`-Zeilen. Bei
  einem einzigen flachen Schlüssel ohne DEK-Schicht wäre Rotation ein Full-Re-Encrypt aller
  Nutzdaten — bei Prod-Volumen ein mehrtägiges Projekt. Hier zahlt sich die DEK-Schicht bei
  echtem Volumen aus, nicht nur konzeptionell.

---

## Betroffene Dateien (nur zur späteren Referenz, falls umgesetzt)

- `src/main/kotlin/com/example/dpop/account/internal/AccountClaim.kt` (neues `claim_batch_id`-Feld)
- `src/main/kotlin/com/example/dpop/account/AccountService.kt` (`recordClaims`: eine `claim_batch_id` pro Aufruf erzeugen)
- `src/main/kotlin/com/example/dpop/account/internal/AccountAnchor.kt` (unverändert — Referenz für die Abgrenzung in Abschnitt 3a)
- `src/main/kotlin/com/example/dpop/account/internal/AccountRetraction.kt`
- `docs/12-entscheidungen.md` (neues ADR für diese Entscheidung)
- `docs/07-betrieb.md` (bestehendes `*RetentionJob`-Muster erweitern)

## Nächster Schritt

Offene Anschlussfragen, falls dieses Vorhaben weiterverfolgt wird: (1) AMK-Verwahrung
(Abschnitt 6), (2) ob Claim-Batch-DEKs für *alle* Attributtypen oder nur für die sensiblen
(EID_*, EMAIL) eingeführt werden, (3) Migrationsstrategie für bereits im Klartext bestehende
Claims, (4) ob die 1-Jahres-Frist für eID-Daten auch die `EID_RESTRICTED_ID`-Anker-Zeile treffen
soll und die damit verbundene Wiedererkennungs-Konsequenz (Abschnitt 3a), (5) Einführung von
`RetractionAnchor.RETENTION_POLICY` für automatisch fristbasierte Widerrufe (Abschnitt 4).
