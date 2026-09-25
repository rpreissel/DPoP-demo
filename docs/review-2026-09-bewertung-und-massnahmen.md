# Review 2026-09: Bewertung, Gegenmaßnahmen und Reihenfolge

Stand: 2026-09-25. Ergänzt [review-2026-09-sicherheit-und-konzept.md](review-2026-09-sicherheit-und-konzept.md)
(die Einzelbefunde S-1 … S-8, M-1 … M-13 und die niedrigen Punkte) um die Einschätzung des
Ganzen, die strukturellen Ursachen hinter den Befunden und einen Fahrplan. Wer nur die Einzelbefunde
sucht, liest das andere Dokument; wer entscheiden will, was zuerst passiert, liest dieses.

Die Bewertung setzt voraus, dass die schweren und mittleren Einzelbefunde behoben werden – sie fragt,
was danach bleibt.

---

## 1. Was das Projekt gut macht

- **Die Architektur trägt.** Orchestrator als Zustandsautomat, Verfahrensmodule nur über SPI,
  Evidenz getrennt von Tokens, Anker getrennt von Claims. Die Modulgrenzen sind so sauber, dass
  sechs Reviews unabhängig voneinander je einen Bereich prüfen konnten. `ApplicationModules.verify()`
  und ArchUnit halten die Grenzen auch künftig.
- **Die kryptographischen Grundlagen sind sauber.** DPoP-Validierung, RFC-7638-Thumbprint,
  Replay-Schutz in eigener Transaktion, Peer-Auth, Vergleiche in konstanter Zeit, PBKDF2 mit
  Dummy-Hash: Kein Review fand hier etwas. Das ist der Bereich, in dem die meisten Projekte scheitern.
- **Der Testunterbau ist ungewöhnlich.** Rund 900 Tests im Hauptmodul, überwiegend
  Integrationstests über HTTP mit echter Datenbank. S-1 ließ sich in Minuten reproduzieren und der
  Fix in einem Durchlauf absichern.
- **Die Dokumentation ist maßgeblich.** ADRs mit verworfenen Alternativen, eine Datei je Journey,
  ein Agent-Quickstart. Mehrere Befunde waren Abweichungen des Codes von der Doku – nicht umgekehrt.
- **Der Code erklärt sich.** Fast jede KDoc sagt, *warum* etwas so ist und welcher Fehler dahinter
  stand.

## 2. Vier strukturelle Schwächen

Die Einzelbefunde sind Symptome. Dahinter stehen vier Ursachen; wer nur die Symptome behebt,
bekommt bei der nächsten Journey dieselben Befunde in neuer Form.

### P-1 Invarianten gelten per Konvention, nicht per Struktur

- **Beobachtung:** S-1 brauchte drei Stellen (`findById`, `isCurrent`, `applyOutcome`), die
  *gemeinsam* eine Regel tragen, die nirgends als Typ oder Constraint stand. M-5 (mehrere laufende
  Journeys), M-6 (zwei Lesarten der Aussperr-Schwelle), der tote Zweitkonto-Zweig, die Drosselung
  als Opt-in je Handler – alles Fälle, in denen der Zustandsraum größer ist als das, was der Code
  strukturell verhindert.
- **Ursache:** Dieselbe Information steht dreimal – `ChannelSession.state`,
  `AuthJourney.lifecycle` plus `active` im JSON-State, `ToolSession.expiresAt`. Jede Kopie muss
  bei jedem Übergang nachgezogen werden. Vier ineinander geschachtelte Automaten (Kanal, Journey,
  Sub-Journey, ToolSession, dazu Tool-interne Steps) vervielfachen den Raum mit jeder neuen Journey.
- **Folge:** Niemand hat den gesamten Zustandsraum mehr im Kopf; die Tests decken die Pfade ab,
  die sich jemand vorgestellt hat.

### P-2 Der Anspruch ist unentschieden

- **Beobachtung:** Die Doku sagt „Demo“, die Codequalität sagt „Produktvorbereitung“, das
  OpenShift-Deployment sagt „wir stellen das ins Netz“. S-4 (Trust-all am Profil statt an der
  Umgebung), S-5 (`admin/admin` mit Kommentar „wer das erreichbar macht, setzt es neu“), der
  unauthentifizierte Personenverzeichnis-Mock als loa2-Übernahmepfad, M-12 (`start-dev` im Pod)
  entstanden alle im Zwischenraum dieser drei Ansprüche.
- **Folge:** Der Rand „demo-only“ wächst, und irgendwann verwechselt jemand den Rand mit der Mitte.

### P-3 Die Keycloak-Anbindung führt zwei Wahrheiten

- **Beobachtung:** Orchestrator-Konto und Keycloak-User werden per Best-Effort-Event
  synchronisiert, das Verknüpfungsattribut `orchestratorAccountId` ist nicht eindeutig, E-Mail
  dient als Fallback-Schlüssel (S-2), ein Signaturschlüssel trägt drei Rollen (S-3), die Antwort
  des Orchestrators an Keycloak ist nicht authentisiert (M-9).
- **Ursache:** Spiegeln statt Lesen. Solange Keycloak Attribute *kopiert*, ist jede Änderung am
  Sync ein potenzieller Kontoübernahme-Pfad.

### P-4 Sicherheitsniveaus ohne Nachweis

- **Beobachtung:** Das Modell rechnet mit `loa1` … `loa3` (Begriffe mit regulatorischer
  Bedeutung), und die Policy-Logik (Bump, Cap, Floor) ist dafür ausgereift. Aber der Gerätefaktor
  liefert loa2 auf Zuruf (M-1), `ident-eid` ist ein Mock mit loa3, ein 40-Bit-Freischaltcode gibt
  loa2 (S-8).
- **Folge:** Ausgereifte Logik über unbewiesene Eingaben suggeriert Vertrauen, das nicht da ist.

### Kleinere Punkte

- **Text-Mechanismus:** Hash-IDs als Schlüssel sind in Logs und Tests unlesbar; jede neue
  Fehlermeldung kostet einen Skill-Aufruf.
- **Kommentardichte:** Viele KDocs erzählen Historie („this used to …“), die in ADRs oder ins
  Git-Log gehört.
- **Audit:** `ON DELETE CASCADE` auf Identifikationsnachweisen widerspricht dem sonstigen Anspruch.
- **Frontend** ist deutlich weniger reif als das Backend – vermutlich bewusst.

---

## 3. Gegenmaßnahmen

### Zu P-1: Invarianten erzwingen

In drei Schichten, von „unausdrückbar“ über „von der DB verboten“ bis „vom Test erschöpfend
abgesucht“ – und vorweg eine Reduktion, damit weniger übrig bleibt, das gesichert werden muss.

**Schicht 0 – Zustandsraum verkleinern (Quelle statt Kopie)**

- `active` verlässt das JSON und wird eine Spalte `auth_journey.active_tool_session_id` mit
  Fremdschlüssel auf `tool_session`.
- `ToolSession` bekommt einen echten Status (`RUNNING` / `DONE` / `ABANDONED`) statt nur `expiresAt`.
- „Welches Tool ist dran“ ist danach eine Beziehung zwischen zwei Zeilen, keine Kopie – und die
  DB kann sie sehen.

**Schicht 1 – Unausdrückbar machen (Typen)**

- **`RunningJourney` als eigener Typ.** `JourneyService.findRunning(id)` ist die einzige Quelle
  und filtert `lifecycle == STARTED`. `applyOutcome`, `advance`, `activateTool` nehmen nur
  `RunningJourney`. Die Entity hat keine öffentlichen Setter für `lifecycle`; Übergänge sind
  Methoden auf `RunningJourney`, die `FinishedJourney` zurückgeben. Eine verbrauchte Journey passt
  dann nicht mehr in `advance` – der Compiler verhindert S-1, nicht ein `&&` in `isCurrent`.
- **`LiveChannel` / `EndedChannel`:** `ChannelAccessGuard.requireLive(...)` liefert nur ersteres;
  `finish()` nimmt `LiveChannel`. Der heutige `isTerminal`-Check in `resolveChannel` wird überflüssig.
- **`ToolOutcome.Failed` je Kategorie:** `Failed.Ident(attemptedPersonId, …)`,
  `Failed.LookupAuth(attemptedAccountId, …)`. Ein Handler *kann* die Drossel nicht mehr vergessen (S-6).
- **`AccountInHand`-Wertobjekt** statt `journey.accountId ?: channel.accountId` an drei Stellen –
  inklusive des Bits „hat diese Journey das Konto selbst gebunden“, das heute aus
  `accountId == null` geraten wird.
- **`AnchorRule.userRetractable`** (S-7), **eine** Aussperr-Schwelle (M-6), **eine** Löschoperation
  `deleteProvisionalAccount`, die `isProvisional` selbst prüft (M-13).

**Schicht 2 – Von der DB verbieten (Constraints)**

- `CREATE UNIQUE INDEX ux_journey_running ON auth_journey(channel_session_id) WHERE lifecycle = 'STARTED'` (M-5).
- `CHECK (active_tool_session_id IS NULL OR lifecycle IN ('STARTED','SUSPENDED'))`.
- `CHECK (state NOT IN ('LOGGED_OUT','EXPIRED') OR auth_context_id IS NULL)` auf `channel_session`.
- Partieller Unique-Index `tool_session(journey_id) WHERE status = 'RUNNING'`.
- Die DB ist dabei nicht die primäre Sicherung, sondern der Rauchmelder: Ein neuer Pfad, der eine
  Invariante verletzt, fliegt im Integrationstest mit `DataIntegrityViolationException`.

**Schicht 3 – Den Restraum erschöpfend absuchen (modellbasierter Test)**

- Ein Generator (Kotest Property-Testing) erzeugt zufällige Aktionsfolgen über die echte
  HTTP-API: Kanal anlegen, Tool aktivieren, PATCH mit richtigem oder falschem Wert, wiederholen,
  abbrechen, ausloggen, MANAGE starten, alte ToolSession-ID erneut senden, zweiten Kanal mit
  demselben Schlüssel öffnen.
- Nach jedem Schritt werden globale Invarianten geprüft, unabhängig vom Schritt:
  - Ein `LOGGED_OUT`-Kanal ist nie wieder `AUTHENTICATED`.
  - Eine Journey verlässt `CONSUMED` / `CANCELLED` / `FAILED` nie wieder.
  - Ein Kanal hat höchstens eine `STARTED`-Journey.
  - Ein `AUTHENTICATED`-Kanal hat Evidenz mit mindestens einem Faktor.
  - Je Konto höchstens eine aktive Instanz einer Singleton-Methode.
  - Kein Gerätelink zeigt auf ein gelöschtes Konto.
- Shrinking liefert bei einem Bruch die *kürzeste* Aktionsfolge – das, was für S-1 von Hand
  gemacht wurde, automatisch und für alle Kombinationen.

**Schicht 4 – Sichtbar machen (Invariantenregister)**

- `docs/invarianten.md`: je Eintrag Regel, Mechanismus (Typ / Constraint / Property-Test /
  ArchUnit) und Fundstelle.
- Ein Test prüft, dass jeder genannte Mechanismus existiert (Index vorhanden, Klasse vorhanden,
  Testname vorhanden). „Gilt nur per Konvention“ ist dann ein leerer Mechanismus-Eintrag, den man
  sieht – kein Kommentar, den man überliest.
- Dazu die ArchUnit-Regel: jeder Handler unter `API_V1` hat `@BindingKey` oder steht in einer
  benannten Ausnahmeliste (Default-Deny für DPoP).

**Was nicht passieren sollte:** die Automaten zusammenlegen oder den Orchestrator umschreiben.
Die Trennung Kanal / Journey / ToolSession ist richtig – sie ist nur nirgends erzwungen.

### Zu P-2: Anspruch entscheiden

> **Entschieden ([ADR-35](adr/ADR-035-betriebsanspruch-backend-kern-produktionsreif.md)):** Der
> Backend-Kern ist produktionsreif; simulierte Fremdsysteme bleiben Vorführrahmen hinter Ports;
> Frontends und Ausführungsumgebung werden später gehärtet. Die Optionen unten sind der Stand vor
> der Entscheidung; gültig ist die Mischform aus dem ADR.

- **Ein ADR „Betriebsanspruch“** mit genau einer Aussage: *Dieser Code geht produktiv* oder
  *dieser Code bleibt Referenz*. Alles Weitere leitet sich daraus ab.
- **Wenn Referenz:** OpenShift-Variante als „erreichbare Demo“ deklarieren und dafür ein
  Mindestmaß erzwingen – zufällige Admin-Geheimnisse, Mocks hinter Admin-Auth, Trust-all nur
  lokal – per Start-Check (`DeploymentTopologyCheck`), nicht per Kommentar.
- **Wenn Produkt:** Die Mocks (`ext_personenverzeichnis`-Verwaltung, `ident-eid`, `nect_mock`,
  `kobil_mock`) werden zu eigenen Gradle-Modulen, die nur ein `demo`-Profil lädt; der
  Produktionspfad kann sie nicht mehr sehen.
- In beiden Fällen: Jeder „demo-only“-Kommentar im Betriebspfad wird zu einem Profil- oder
  Varianten-Schalter mit Start-Check.

### Zu P-3: Eine Wahrheit für Keycloak

- **Lesen statt spiegeln.** Der `OrchestratorStorageProvider` (UserStorage, `federationLink`)
  wird die einzige Quelle für Identität, E-Mail und Stammdaten; Keycloak hält keine Kopie mehr,
  `KeycloakAccountSyncListener` und `findMirror` entfallen. Was Keycloak zwingend lokal braucht
  (User-ID, Sessions), bleibt – der Rest ist ein Durchgriff.
- **Bis dahin (Zwischenschritt):** `orchestratorAccountId` eindeutig (Abbruch bei mehr als einem
  Treffer), nie einen User mit anderem `orchestratorAccountId` übernehmen, `email` im
  User-Profile nur für `admin` editierbar, `accountId ≠ restoreData.accountId` → 409.
- **Ein Schlüssel je Rolle** (`purpose`-Spalte existiert); Migrationsclient nur `create-realm`
  plus Realm-Admin des Zielrealms, danach deaktivieren.
- **Antwort authentisieren:** Der Orchestrator signiert seine Antworten an Keycloak (oder TLS auf
  dem Hop); ADR-7 hält die Netzannahme ausdrücklich fest.
- **Realm-Neuaufbau** nur mit explizitem Schalter.

### Zu P-4: Niveaus ehrlich vergeben

- **Descriptor sagt nur, was er beweisen kann.** Ohne Plattform-Attestation ist `auth-device`
  `{possession}`, loa1; loa2 kommt aus der Kombination mit einem zweiten Verfahren (die Policy
  kann das bereits).
- **Gerätefaktor unabhängig vom Kanalschlüssel:** `thumbprint == bindingKeyRef` beim Enrollment
  ablehnen; als Test verankert.
- **Simulierte Verfahren über ihren Port-Vertrag:** `ident-eid` bleibt Simulation des eID-Servers
  mit dessen Niveau; der Port-Vertrag hält fest, dass ein reales Ergebnis serverseitig vom
  eID-Server kommt und nie aus Client-Angaben (ADR-35).
- **Freischaltcode:** bleibt bis zum Ablauf wiederverwendbar – bewusst, wegen der Re-Identifizierung
  (ADR-31). Hash mit Pepper und Länge sind Sache des Fremdsystems (ADR-35).
- **Ein ADR „Niveaus und ihre Nachweise“:** je Stufe, welcher Nachweis sie trägt und welcher Test
  das prüft.

### Zu den kleineren Punkten

- **Text-Mechanismus:** lesbare Schlüssel (`journey.session_ended`) statt Hash; die Vorlage bleibt
  im Code, der Hash wird nur zur Änderungserkennung genutzt. Kein Muss – aber die Hürde für neue
  Texte sinkt.
- **Kommentare:** Historie in ADRs oder Commits, KDoc nur für das Warum des *aktuellen* Zustands.
- **Audit:** ADR „Was eine Kontolöschung überlebt“ – mindestens `identification` und `retraction`
  ohne Kaskade, Methodenlebenszyklus als Append-only-Zeilen.

---

## 4. Globale Reihenfolge

Die Reihenfolge folgt drei Regeln: Erst, was heute ausnutzbar ist. Dann das Sicherheitsnetz, bevor
umgebaut wird. Dann die Umbauten, jeweils vom günstigsten zum teuersten. Doku-Abweichungen werden
mit dem jeweiligen Schritt korrigiert, nicht gesammelt.

**Phase A – Ausnutzbares schließen (Tage)**

1. ~~S-1 Tool-Replay~~ – erledigt 2026-09-25.
1b. ~~ADR „Betriebsanspruch“~~ – entschieden 2026-09-25 als
    [ADR-35](adr/ADR-035-betriebsanspruch-backend-kern-produktionsreif.md): Backend-Kern
    produktionsreif, Frontends und Umgebung später. Die Schritte unten sind danach zugeschnitten;
    was in die Umgebung gehört, steht gesammelt in Phase G.
2. ~~S-2 Keycloak-Sync~~ – erledigt 2026-09-25.
3. ~~S-3 Ein Schlüssel je Keycloak-Client, Migrationsclient ohne Master-Admin~~ – erledigt 2026-09-25.
4. ~~S-4 Trust-all nur per Schalter und nur für Keycloak-Verbindungen~~ – erledigt 2026-09-25.
   (S-5 und M-12 sind Umgebung → Phase G.)
5. ~~S-6 KVNR-Orakel~~ – erledigt 2026-09-25.
6. ~~S-7 Widerruf nur für EMAIL~~ – erledigt 2026-09-25.
7. ~~S-8 Freischaltcode~~ – entschieden 2026-09-25: bleibt bis zum Ablauf wiederverwendbar,
   Restrisiko in ADR-31 festgehalten; Hash und Länge bleiben Fremdsystem (ADR-35).
8. ~~M-2 QR-Login~~ – erledigt 2026-09-25 als Code in Gegenrichtung (App zeigt, Browser tippt).
9. ~~M-8 Keycloak-Härtung (Raten, Mgmt-Passwort, Sperren)~~ – erledigt 2026-09-25.
10. ~~M-11 TANs nicht auf STDOUT~~ – erledigt 2026-09-25 (simulierte Anbieter mit Postausgang, ArchUnit).

**Phase B – Sicherheitsnetz spannen, bevor umgebaut wird (Tage)**

11. ~~Modellbasierter Test~~ – erledigt 2026-09-25: `ModelBasedJourneyTest` (200 Seeds,
    Schrumpfen auf die kürzeste Folge) prüft I-1 bis I-4, I-13, I-14 nach jedem Schritt. Gegenprobe:
    ohne den S-1-Fix findet er S-1 selbst. Gefunden hat er M-5 – eingeschoben und behoben
    (`JourneyService.start` bricht die laufende Kette ab).
12. ~~ArchUnit `@BindingKey`-Regel und Invariantenregister~~ – erledigt 2026-09-25:
    `ApiBoundaryArchitectureTest`, [invarianten.md](invarianten.md) mit `InvariantRegisterTest`.
13. ~~ArchUnit-Grenze Kern ↔ Fremdsystem-Simulation~~ – erledigt 2026-09-25:
    `SimulationBoundaryArchitectureTest`; der Keycloak-Sync liest Stammdaten jetzt über den neuen
    Port `PersonMasterData` statt direkt aus dem simulierten Personenverzeichnis.

**Phase C – Zustandsraum verkleinern und verbieten (eine Woche)**

14. ~~Schicht 0: `active_tool_session_id` als Spalte~~ – gestrichen 2026-09-25: Was die Spalte
    sichern sollte, decken der S-1-Fix, der modellbasierte Test und die Typen aus Phase D ab; der Umbau
    quer durch alle Zustandsklassen lohnt sich dafür nicht mehr. Umgesetzt ist nur `ToolSession.status`
    (`RUNNING`/`DONE`/`ABANDONED` statt einer vorgezogenen Ablaufzeit).
15. ~~Schicht 2: Constraints~~ – erledigt 2026-09-25: I-3 und I-13 per berechneter Spalte mit
    Unique-Index (Ersatz für partielle Indizes, die H2 nicht kennt), I-1 und I-4 als CHECK; vorher
    Bereinigung von Altdaten; gegen die gefüllte Container-Datenbank erprobt.
    `DatabaseInvariantConstraintTest` bricht jede Regel direkt per SQL.
16. ~~M-4~~ – erledigt 2026-09-25 (Ablauf beendet die Anmeldung, gleitendes Refresh-Fenster,
    `JourneyService.endSession` für beide Abmeldewege).
17. ~~M-6~~ entschieden 2026-09-25: bleibt, Re-Identifizierung ist der Weg zurück
    (journeys/manage-auth-methods.md). ~~M-13~~ – erledigt 2026-09-25 (Nachweise beim Ersetzen,
    Management-Pfad angeglichen, `deleteProvisionalAccount`, Listener einspurig mit Konfliktauflösung).

**Phase D – Typen (ein bis zwei Wochen, journeyweise)**

18. ~~`RunningJourney` / `FinishedJourney`~~ – erledigt 2026-09-25: `JourneyService` nimmt von
    außen nur noch `RunningJourney` an, dessen einzige Fabrik eine beendete oder abgelaufene Journey
    ablehnt; die Lebenszyklus-Prüfung in `isCurrent` ist entfallen. ArchUnit hält fest, dass außerhalb
    des Journey-Pakets nur die Aufbewahrung `AuthJourney` berührt. `FinishedJourney` entfällt – es
    gibt keinen Aufrufer, der mit einer beendeten Journey arbeitet. Der `isTerminal`-Check am Kanal
    gehört zu Schritt 19.
19. ~~`LiveChannel` / `EndedChannel`~~ – erledigt 2026-09-25: `JourneyService` startet, bewegt und
    beendet Journeys nur noch auf einem `LiveChannel`; `ChannelAccessGuard.requireLiveChannel` für
    alles, was den Kanal bewegt. Der Umbau fand zwei echte I-1-Verletzungen, die kein Test abdeckte:
    Ein Step-up auf einem abgemeldeten oder abgelaufenen Kanal startete eine Journey und setzte ihn
    auf `STEP_UP_IN_PROGRESS` (danach ließ sich wieder ein Tool aktivieren), und ein GET auf einen
    `EXPIRED`-Kanal startete seine Einstiegs-Journey neu. Der modellbasierte Test kennt jetzt den
    Step-up und prüft, dass ein beendeter Kanal seinen Endzustand behält. `EndedChannel` entfällt
    wie `FinishedJourney`: Lesen darf jeder Kanal, dafür braucht es keinen eigenen Typ.

    Mit dem Step-up fand der Modelltest zwei weitere Fehler. Ein Step-up vor der Anmeldung endete
    ohne Konto mit 500. Mit einem über das Gerät bekannten Konto meldete sein Abbruch den Kanal ohne
    jeden Nachweis als `AUTHENTICATED` – dasselbe galt für den Abbruch einer kalt gestarteten
    Web-Login-Bestätigung. Seit V22 lehnte die Datenbank das ab (I-4), vorher wäre es still
    durchgegangen. Ursache war, dass jede Strategie ihren Rückfallzustand selbst benannte
    (`cancelledTo`) und sechs davon voraussetzten, nur auf angemeldeten Kanälen zu laufen. Jetzt gilt
    eine Regel für alle Intents: Ein Abbruch kehrt zum Anmeldestand vor der Journey zurück
    (`ChannelState.isLoggedIn`); `cancelledTo` ist aus der SPI entfernt, `STEP_UP_IN_PROGRESS`
    setzt der Code nur noch auf einem angemeldeten Kanal, ein Step-up vor der Anmeldung hebt nur die
    Untergrenze der laufenden Anmeldung, und ein Abbruch beendet die ganze Kette statt eine
    pausierte Eltern-Journey liegen zu lassen.
20. ~~`ToolOutcome.Failed` je Kategorie~~ – erledigt 2026-09-25: je Rolle statt je Kategorie
    (`AUTH` fasst `IDENTIFIED_AUTH` und `LOOKUP_AUTH` zusammen, deren Subjekt verschieden
    herkommt): `IdentifiedAuth`, `LookupAuth(attemptedAccountId)`, `Identification(attemptedPersonId)`,
    `NothingGuessed`. Das Subjekt ist Pflicht, `null` steht ausdrücklich an der Aufrufstelle; die
    Drosselbuchung ist ein erschöpfendes `when` über die Varianten, und eine Variante, die nicht zur
    Rolle passt, bricht vor jeder Buchung ab. Bei der Umstellung stand jede „niemand“-Entscheidung
    einzeln zur Prüfung (`ident-fsc` bei unbekannter Person, `ident-eid`, `ident-nect`,
    `auth-qr-lookup` mit eigenem Versuchsbudget); `auth-kobil` gab überflüssig ein Konto mit, das
    der Kanal schon kennt.
21. ~~`AccountInHand`~~ – erledigt 2026-09-25, schlanker als geplant: Es gibt kein Wertobjekt,
    sondern eine Quelle. `journey.accountId ?: channel.accountId` stand an elf Stellen, obwohl beide
    in jedem erreichbaren Fall gleich sind (der Start kopiert, jede Bindung schreibt beide). Jetzt
    liest jede Entscheidung `ChannelSession.accountId`; `AuthJourney.accountId` bleibt als
    geschriebener Nachweis und wird per ArchUnit von keinem Produktionscode gelesen. Das Bit „von
    dieser Journey gebunden“ brauchte nur der tote Zweitkonto-Zweig – der ist entfernt, die Doku
    (04 §2, journeys/register.md) beschreibt jetzt das tatsächliche Verhalten: ein zweites Konto auf
    einem verknüpften Gerät nur über `intent=register`. Im `tool_api` sind `journeyAccountId` und
    `channelAccountId` zu einem `accountId` zusammengelegt.

**Phase E – Niveaus und Keycloak**

22. ~~M-1 Gerätefaktor~~ – erledigt 2026-09-25, anders als geplant: Statt den Descriptor ohne
    Attestation auf `{possession}` zu setzen, erklären sich die Geräte-Tools als `demoOnly` und sind
    außerhalb des neuen `demo.mode` aus (Entscheidung Rene: „nur im Demomodus“, dann „bei den Tools
    definieren, ob sie nur Demo sind“). Ebenso `demoOnly`: KOBIL, eID und Nect, deren Niveau auf
    einer simulierten Gegenstelle beruht. `thumbprint ≠ bindingKeyRef` gilt immer.
    [ADR-36 „Niveaus und ihre Nachweise“](adr/ADR-036-niveaus-und-ihre-nachweise.md) (P-4).
23. ~~M-3~~ – entschieden 2026-09-25: Keycloak-Tokens werden nicht gebunden; die Grenze steht in
    ADR-9 und docs/09 Abschnitt 4.
24. ~~M-7~~ – entschieden 2026-09-25: bleibt, abgewogen in ADR-37.
25. **M-9 / M-10** Antwort an Keycloak signieren (TLS auf dem Hop ist Umgebung → Phase G),
    Realm-Neuaufbau nur mit Schalter.
26. **P-3 langfristig:** Keycloak liest statt spiegelt; Sync-Listener entfällt.
27. **Port-Verträge der Fremdsysteme vervollständigen** (ADR-35): je Port, was ein reales System
    zusagen muss (Signatur, Frische, serverseitiges Ergebnis, Ablage ohne Klartext-Rückschluss) –
    KOBIL, Nect, eID, Personenverzeichnis.

**Phase F – Härtung und Hygiene (nach Bedarf)**

28. Niedrige Befunde nach Aufwand: Versanddrossel bei Aktivierung, Rufnummern-Allowlist, geteilte
    `DeviceEnrollment`-Zeile, `enrolledUnderAcr` je Instanz, Widerruf-`>=`, toter KVNR-Zweig,
    Namensvetter-Entscheidung (ADR-18), PBKDF2-Iterationen, KOBIL-Geheimnisse kürzer, `DPoP-Nonce`
    in docs/09, Fehlerantworten ohne Bibliotheksmeldungen, `jti` in der Konto-Assertion,
    JWKS-Backoff.
29. **ADR „Audit“** und Umsetzung (Kaskaden entfernen, Append-only-Zeilen).
30. Text-Schlüssel lesbar machen; Historie aus KDocs in ADRs verschieben.

**Phase G – Frontends und Ausführungsumgebung (später, nach ADR-35)**

Nicht Teil der jetzigen Runde. Bis sie erledigt ist, läuft keine Instanz mit echten Personendaten.

31. **S-5** Admin-Geheimnis auf OpenShift per Secret, Start-Check gegen das Default-Passwort.
32. **M-12** Keycloak `start --optimized` mit festem Hostnamen; H2-Konsole hinter Admin-Auth,
    DB-Passwort per Secret.
33. TLS zwischen Keycloak und Orchestrator (Rest von M-9); `forward-headers-strategy` mit
    vertrauenswürdigen Proxies; Compose-Ports auf `127.0.0.1`.
34. Frontend: CSP-Header, Web-Kanal-Tokens (Refresh-Token-Regel, `state`/`iss`),
    Frontend-Thumbprint nach RFC 7638.
35. Verwaltungs-APIs der simulierten Fremdsysteme hinter Admin-Auth oder per Profil abschaltbar.

---

## 5. Was dieses Dokument nicht entscheidet

- Den Betriebsanspruch entscheidet [ADR-35](adr/ADR-035-betriebsanspruch-backend-kern-produktionsreif.md),
  nicht dieses Dokument; die Reihenfolge ist danach zugeschnitten.
- Ob der Web-Kanal die Regel „Refresh-Token nie ins Frontend“ übernehmen soll – das ist eine
  Frage an den Anspruch des Web-Kanals, nicht an seine Sicherheit.
