# Zweite Bewertung (2026-09-26): Stand nach Phase A–F, neue Befunde, neue Reihenfolge

Grundlage: die erste Bewertung ([review-2026-09-bewertung-und-massnahmen.md](review-2026-09-bewertung-und-massnahmen.md),
Phasen A–F umgesetzt), vier getrennte Prüfungen des heutigen Stands (Sicherheit, Architektur und
Vereinfachung, Tests/Betrieb/Skalierung, Doku-Konsistenz) und eigene Nachprüfung jedes Befunds, der
hier als „hoch“ steht. Maßstab ist [ADR-35](adr/ADR-035-betriebsanspruch-backend-kern-produktionsreif.md): Der Kern soll
produktionsreif sein, jede Sicherheitszusage gilt ohne unbenannte Annahme an die Umgebung.

## 1. Urteil in drei Sätzen

- **Der Ansatz trägt.** Reine Strategien, ein schreibender Executor, ein Weg nach AUTHENTICATED,
  Modulgrenzen und Invarianten per ArchUnit und Datenbank-Constraint, Replay-Schutz als Primärschlüssel,
  signierte Antworten zwischen Keycloak und Orchestrator, Widerruf als eigene Zeile, ein Änderungsprotokoll
  ohne Werte: Das überzeugt kritische Kolleginnen und Kollegen von der Richtung.
- **„Produktionsreif“ hält der Kern heute noch nicht.** Ein Sicherheitsexperte findet in der ersten Stunde
  drei Dinge: Der Konto-Token-Grant steht jedem Client des Realms offen, ein Namensvetter mit fremder
  KVNR kann ein bestehendes Konto übernehmen, und nichts hindert einen Start mit Demo-Voreinstellungen
  (`admin/admin`, H2-Datei, Klartext-Schlüssel) bei `demo.mode=false`. Dazu kommt: Es gibt nur H2, und
  genau auf der Betriebs-URL löscht `FlywayResetConfig` bei jedem Migrationsfehler die Datenbank samt
  Zehn-Jahres-Protokoll.
- **Das größte Verständnisrisiko ist die Doku, nicht der Code.** Die Keycloak-Anbindung wurde in Phase E
  auf Lesen statt Spiegeln umgestellt (ADR-38); 03, 04, ADR-8/9/29/34 und `application-keycloak.yml`
  beschreiben weiter die Spiegelung. Weil „Doku hat Vorrang vor Code“ gilt, würde ein Leser (oder ein
  Agent) die Spiegelung wieder einbauen.

## 2. Was seit der ersten Bewertung geklärt ist

Die vier strukturellen Schwächen P-1 bis P-4 sind behoben und im Code nachvollziehbar: Invarianten per
Typ und Constraint (`RunningJourney`, `LiveChannel`, `Failed`-Varianten je Rolle, `ToolSession.status`,
`DatabaseInvariantConstraintTest`), der Anspruch entschieden (ADR-35/36, `demoOnly`), eine Wahrheit für
Keycloak (ADR-38, Federation ohne Import), Niveaus mit Nachweis (ADR-5-Deckel im Claim-Log). Die
kleineren Punkte sind erledigt: Text-IDs lesbar, Historie aus den Kommentaren, Änderungsprotokoll
`account.change_log` (auffindbar über Name/Vorname/Geburtsdatum), Anmeldeprotokoll `account.sign_in_log`,
Argon2id, Rufnummern-Allowlist, Versanddrossel, Bezeichner englisch, Register nur über Ports.

## 3. Neue Befunde

Reihenfolge innerhalb jedes Abschnitts nach Schwere. Jeder Punkt nennt, was zu tun ist:
**Code**, **Entscheidung** (ADR) oder **Doku**.

### 3.1 Sicherheit

- **F-1 (hoch, Code) Konto-Token-Grant ohne Client-Beschränkung.** `AccountTokenGrantType.java` prüft den
  aufrufenden Client nicht; auch der öffentliche Browser-Client `dpop-demo-web` kann
  `grant_type=urn:dpop-demo:account-token` aufrufen. Einziges Tor ist die Konto-Assertion. Fix: Client-Id
  gegen die konfigurierte Orchestrator-Client-Id prüfen, öffentliche Clients ablehnen; Test in der Extension.
- **F-2 (hoch, Entscheidung + Code) Namensvetter übernimmt ein Konto per `ident-kvnr`.**
  `IdentityMatchingService.attestedIdentityMatches` vergleicht nur Name, Vorname, Geburtsdatum;
  `JourneyActionExecutor.accountOf` bindet ein vorläufiges Konto dann auf das gefundene um und absorbiert
  es. Ablauf: Ein Angreifer mit gleichem Namen und Geburtsdatum identifiziert sich per eID mit *seinen*
  Daten, tippt die KVNR des Opfers (steht auf der Karte, kennt jeder Arzt), und ist auf dem Opfer-Konto
  angemeldet. ADR-18 nimmt an, ein Namensvetter habe „eine andere KVNR“; er tippt aber die fremde. Fix
  als Nachtrag zu ADR-18: Die Auflösung auf ein bestehendes, nicht vorläufiges Konto braucht einen zweiten
  Akt (Freischaltcode-Brief oder Bestätigung mit einem Faktor des Zielkontos), oder das Restrisiko wird
  mit der Kollisionsrate im Bestand ausdrücklich getragen.
- **F-3 (hoch, Code) Kein Start-Check gegen Demo-Voreinstellungen.** Bei `demo.mode=false` startet die
  Anwendung mit `admin/admin` (`{noop}`), H2-Datei-DB, H2-Konsole, leerem OTP-Pepper, http-Basis-URLs,
  `trustSelfSignedCertificate: true`. Geprüft wird verstreut (`PersonLookupKey`, `DeploymentTopologyCheck`,
  Realm-Reset). Fix: ein `ProductionModeCheck` beim Start, der jede dieser Voreinstellungen mit einer
  Liste abweist; Test, dass der Kontext bei `demo.mode=false` mit Defaults nicht startet.
- **F-4 (hoch, Code) Versanddrossel von `enroll-sms` greift nicht.** Der Controller keyt die Drossel mit
  „Leerzeichen entfernt“, der Flow normalisiert `[\s\-/().]` und `00`→`+`. `+49-170…`, `+49/170…`,
  `0049170…` sind für die Drossel verschiedene Kontakte, für den Versand dieselbe Nummer; der Schritt
  kostet kein Versuchsbudget und läuft in REGISTER anonym. Fix: `PhoneNumber`-Wertobjekt in `tool_api`
  (Vorbild `Email`), Controller und Handler nutzen es; Test mit drei Schreibweisen.
- **F-5 (mittel, Entscheidung) Vertrauensanker der Antwortsignatur kommt über denselben Hop.**
  `OrchestratorResponseVerifier` holt das JWKS von `orchestratorBaseUrl` (http in compose/OpenShift).
  Ein aktiver Angreifer auf diesem Hop liefert sein eigenes JWKS. Fix: Antwort-Schlüssel in der
  Keycloak-Komponente pinnen wie den Peer-Auth-Schlüssel, oder https außerhalb des Demomodus erzwingen (F-3).
- **F-6 (mittel, Entscheidung) Schlüsselpaar je Konto ohne Sicherheitsgewinn.** `keycloak_keypair` und
  `node_signing_key` liegen im Klartext in derselben Datenbank, nie rotiert. Ein gestohlener
  Konto-Schlüssel erlaubt (mit F-1 von jedem Client) Tokens mit frei gewähltem `acr`/`amr`. Der Schlüssel
  je Konto isoliert nur den Fall „Client-Schlüssel leckt allein“, der bei gleicher Ablage nicht vorkommt,
  und kostet eine Lookup-Runde je Grant, `REQUIRES_NEW`-Reihenfolge und den Multi-Instanz-Konflikt. Fix:
  ADR-9 überarbeiten, einen Orchestrator-Schlüssel (`NodeKeys`, `purpose`) mit `sub`, `jti`, kurzem `exp`.
  Per-Konto-Schlüssel lohnen erst mit KMS/HSM.
- **F-7 (mittel, Code) Admin-Anmeldung ohne Sperre, Demo-Reset auch außerhalb des Demomodus.**
  `AdminSecurityConfig` ohne Drossel und mit `{noop}`; `AdminAccountsController.demo-reset` löscht alle
  Konten und ist nicht `@DemoSurface`. Fix: Reset und Seed hinter `@DemoSurface`, `AttemptCounter`-Scope
  für Admin-Logins, Passwort-Hash.
- **F-8 (mittel, Code) QR-Bestätigungscode ohne Pepper.** `PairingCodeGenerator.digest` ist nackter
  SHA-256 eines sechsstelligen Codes; TAN und E-Mail-Code begründen für genau diesen Fall den HMAC-Pepper.
  Fix: derselbe `otp-pepper`-HMAC.
- **F-9 (mittel, Entscheidung) Aufzählung und Zeitverhalten der Lookup-Tools.** Mit `demo.disclosure`
  (Default `= demo.mode`) verrät `demo.tan` in `auth-*-lookup`, ob eine Adresse ein Konto hat. Das ist
  im Demomodus gewollt, steht aber nirgends. Mit echtem SMS-/Mail-Anbieter wird die Versandlatenz zum
  Orakel. Fix: Doku (04) und Entscheidung „Versand entkoppelt (Outbox-Queue), bevor ein echter Anbieter kommt“.
- **F-10 (niedrig, Code) Kleinere Härtungen.** Peer-Auth-Assertion ohne `typ`, RS256 zugelassen, Anker bei
  frischem Kanal nicht `== channelSessionId`; DPoP-`htu`-Vergleich case-insensitiv über die ganze URL
  (RFC 9449: Pfad exakt); `otp-pepper` ohne Mindeststärke; `Email` ohne Längenlimit (254);
  `MgmtPasswordController.set` wirft `IllegalArgumentException` statt 400; `findDeviceLink` liefert den
  Klarnamen vor jedem Beweis; RestoreData-Secret je Prozess nicht in `DeploymentTopologyCheck` genannt;
  Legacy-PBKDF2-Hashes ohne Stichtag; Änderungsprotokoll-HMAC ohne `kid`/Rotationspfad.

### 3.2 Datenbank, Betrieb, Skalierung

- **B-1 (hoch, Code) Nur H2.** Kein Postgres-Treiber, kein Testcontainers; Betriebs-URL in `compose.yml`
  und `openshift/dpop-demo.yaml` ist `jdbc:h2:file`. Migrationen enthalten H2-Spezifika
  (`GENERATED ALWAYS AS` ohne `STORED` in V22/V23, `RAWTOHEX(HASH(...))` in `demo_seed/V16`), und
  `demo_seed` läuft in jeder Datenbank, weil `ModuleMigrationLocations` jeden Ordner einsammelt. Fix:
  Postgres-Treiber und -Profil, ein Testcontainers-Test „migrate + `ddl-auto: validate`“, `demo_seed` nur
  bei `demo.mode`, berechnete Spalten `STORED`. Erst damit ist „10 Millionen Konten“ eine Aussage.
- **B-2 (hoch, Code) `FlywayResetConfig` löscht die Betriebsdatenbank.** Bedingung ist nur „URL beginnt
  mit `jdbc:h2:file:`“, also die Betriebs-URL. Jede `FlywayException` (Checksum, Syntaxfehler in einer neuen
  Migration) löscht Konten und Änderungsprotokoll und baut leer neu. Fix: Bean nur bei `demo.mode=true`,
  sonst Fail-Fast; Test.
- **B-3 (hoch, Code) Unbeschränkte Keycloak-Probe im `RetentionJob`.**
  `findByChannelAndExpiresAtBefore(KEYCLOAK, now)` ohne `Pageable` lädt stündlich jede abgelaufene, noch
  nicht bestätigte Keycloak-Sitzung bis 30 Tage zurück und ruft je Zeile synchron die Admin-API. Fix:
  Die Frage umdrehen: Keycloak meldet Logouts jetzt ohnehin (`SignInLogEventListener`);
  `signedOutAtKeycloak` beendet den Kanal direkt, die Probe entfällt. Übergangsweise paginieren mit
  Obergrenze je Lauf.
- **B-4 (mittel, Code) Fehlervertrag ohne Fallback.** `OrchestratorExceptionHandler` kennt 11 Typen, keinen
  `Exception`/`DataAccessException`; ein Datenbankausfall liefert Boots Standardkörper statt
  `ErrorResponse{INTERNAL_ERROR}` (07 §1 verspricht die Form für jede Antwort). Fix: Fallback-Handler, Test.
- **B-5 (mittel, Code + Doku) Kein Health, keine Kennzahlen.** Kein Actuator, kein Micrometer; die
  OpenShift-Probes treffen `GET /`. Fix: Actuator (health mit DB und Keycloak-Erreichbarkeit), Kennzahlen für
  Sweeps, Drossel-Treffer, Keycloak-Latenz, `event_publication`-Rückstand.
- **B-6 (mittel, Doku + Prozess) Backup und Restore fehlen.** Kein Wort zu `account.change_log`
  (10-Jahres-Nachweis), `CHANGE_LOG_LOOKUP_SECRET` (ohne ihn ist das Protokoll unauffindbar), den
  Schlüsseltabellen und der Reihenfolge mit dem Keycloak-Realm. Fix: Abschnitt in 07 mit RPO/RTO und
  geprobtem Restore.
- **B-7 (mittel, Code) Aufräumpfade.** `SessionRetentionSweeper.deleteChannels` löscht per JPA-Einzelstatement
  (07 §6 verspricht eine SQL-Anweisung je Lauf); `SignInLogRetention.deleteOlderThan` löscht Millionen
  Zeilen in einer Transaktion (Batches wie `ChangeLogRetention`); `ChannelSession` lebt 30 Tage mit einer
  Begründung (Trace), die nur noch 14 Tage gilt: bei einer Sitzung je App-Start sind das 300 Mio. Zeilen.
  Fix: Bulk-Delete, Batches, 14 Tage.
- **B-8 (mittel, Doku + Code) Acht `@Scheduled`-Jobs ohne Sperre.** 07 §3b und `DeploymentTopology`
  zählen drei. Doppellauf ist idempotent, also erst vor Multi-Instanz kritisch. Fix: Doku und Check
  angleichen, später ShedLock oder Postgres-Advisory-Lock.
- **B-9 (niedrig, Code) Logging.** Kein MDC (channelSessionId, accountId), kein strukturiertes Format;
  4xx-Meldungen auf INFO. Fix: MDC-Filter, `logging.structured.format=ecs`.

### 3.3 Architektur und Vereinfachung

- **A-1 (hoch, Doku + Code) Doku-Drift Keycloak.** `KeycloakAccountSyncListener` hört nur noch auf
  `AccountDeleted`; 03 §Keycloak, 04 §Keycloak, ADR-8/9/29/34, `application-keycloak.yml:26-29`,
  `V1__realm.kc.kts` (nennt `KeycloakOidcTokenValidator`, vergibt `view-realm` für eine gelöschte
  Funktion) beschreiben die Spiegelung. Dazu tote Reste: `KeycloakSyncExecutorConfig` (Ein-Thread-Executor
  gegen 409-Konflikte, die es nicht mehr gibt), `spring.task.execution.mode: force`,
  `KcAccountView.authMethods`, `KEYCLOAK_ATTRIBUTE_TYPES`, `AccountKeypairService` mit `REQUIRES_NEW` und
  veralteter Begründung. Fix: Listener in `KeycloakAccountRemovalListener` umbenennen, Executor und Reste
  streichen, Doku und ADRs auf ADR-38 ziehen.
- **A-2 (mittel, Entscheidung) Das Experiment „Enrollment zuerst“ verdoppelt die zentrale Journey.**
  `RegisterStrategy` und `RegisterEnrollFirstStrategy` mit je eigenem State-Set, gewählt über ein
  Laufzeit-Flag; 15 Dateien im Hauptcode kennen das Experiment, dazu ein Sonderfall im `JourneyStateCodec`
  und eine `FeatureFlagProvider`-Liste mit einer Implementierung. Fix: entscheiden. Entweder eine
  Reihenfolge streichen (rund 500 Zeilen plus Flag-Infrastruktur) oder beide als benannte Intents
  (`REGISTER`, `REGISTER_ENROLL_FIRST`) ohne Laufzeit-Flag.
- **A-3 (mittel, Code) 22 Tool-Controller, ein Gerüst.** `read()` wortgleich in 21, das Ende von `activate()`
  in 22, `X_TOOL_ID` neben `descriptor.toolId`; 21 Session-Entities mit gleichem `init`, 23 Repositories mit
  derselben Lösch-Query, 10 Retention-Jobs, die nur Repositories aufzählen; die `EnrollmentRef`-Prüfung
  dreifach kopiert; tote Spalte `enrollmentRefType` in vier Sessions; `IdentEidToolController` trägt einen
  aus FSC kopierten Kommentar zu einer Sperre, die dort gar nicht anwendbar ist. Fix ohne generischen
  Dispatcher: `ToolEndpoint.readResponse`/`created`, `EnrollmentRef.requireLocalId`, `@MappedSuperclass`
  für Tool-Sessions mit Basis-Repository; einen Drossel-Stil festlegen; Naming angleichen
  (`IdFsc*`/`IdentFsc*`, `authsmsuse`/`authqr`).
- **A-4 (mittel, Code) Drei Mechanismen für „Tool an/aus“ plus zwei Flag-Speicher.** Client-`availableTools`
  (richtig), Tabelle `tool_availability` mit yml-Seed `demo.tool-defaults`, `demoOnly × demo.mode`; daneben
  `feature_flag` mit eigenem Service. Fix: yml-Seed streichen (Tabelle ist die Wahrheit, ein Reset reicht),
  `feature_flag` in die Admin-Einstellungen aufgehen lassen, `demo.disclosure` streichen (immer `= demo.mode`).
- **A-5 (mittel, Doku) ADRs: 39, davon rund 20 tragend.** Widersprüche: ADR-31 (Titel „fragt direkt“,
  Nachtrag und Code sagen Port), ADR-38 löst Teile von ADR-8/9/29/34 ab, ohne dass die es sagen, ADR-20
  (`absorbedFromAccountId` statt `carriedFromAccountId`), ADR-5 (Datei „drei“, Titel „zwei“, Spalte
  `acr` statt `achieved_acr`), ADR-19 vs. 02 (`VERSNR`/`INSURANCE_NUMBER`). Keine Entscheidungen: ADR-22,
  27, 33 (Handbuch), 35 (Programm). Fix: Stubs 4/13/23/30 archivieren; zusammenlegen 5+36 (Niveaus),
  10/17/18/19/20/37 (Identität), 7/8/9/25/38 (Keycloak), 21+22 (KOBIL-PIN); Titel und Index korrigieren.
- **A-6 (niedrig, Code) Zuschnitt der großen Klassen.** Nicht zerlegen, aber: `ChannelService` mischt
  Anwendungsfälle mit Antwortbau (~150 Zeilen in einen `ChannelResponseAssembler`); `AccountService`
  trägt Claims-Ledger, Anker-Register und Methodenverwaltung (intern in `ClaimLedger` + `AnchorRegistry`
  teilen, Fassade schmal halten); `journey.accountId` wird dreimal geschrieben, nie gelesen;
  `orchestrator.session.AuthEvidence` (JPA) vs. `policy.AuthEvidence` (Wert) brauchen einen Import-Alias
  (Entity in `EvidenceTrail` umbenennen). Tot: `session/ToolState.kt`, `resolveAccountByInsuranceNumber`.
  Bezeichner: `versnr` in `PersonChanged`, `PersonMasterData`, `AccountDirectoryExtensions`.
- **A-7 (niedrig, Entscheidung) Text-Mechanismus.** Stimmig, aber schwer: sechs Implementierungen der
  ID-Regel, Katalog per ASM-Bytecode-Analyse, eigene ETag-Auslieferung. Stufe a: Katalog per
  Quelltext-Regex statt ASM. Stufe b (später, kippt ADR-33): handvergebene Schlüssel mit `MessageSource`.

### 3.4 Tests

- **T-1 (mittel) Ungetestet:** Step-up im Anmeldeprotokoll (`STEPPED_UP`, direkt und als Unter-Journey),
  `SignInLogEventListener` (Filter und After-Commit), `DeploymentTopologyCheck`, `FlywayResetConfig`
  (darf außerhalb H2/Demo nichts löschen), der neue `ProductionModeCheck` (F-3).
- **T-2 (mittel) `RetentionJobTest` pinnt die Implementierung** (relaxed Mocks plus `verify`); für
  `SessionRetentionSweeper` fehlt ein Datenbanktest der Besitzkette wie `ChangeLogDbTest`.
- **T-3 (niedrig) Determinismus und Parallelität.** Scheduling läuft in allen 41 gecachten Testkontexten;
  drei Tests pollen mit `Thread.sleep`; keine Tests für `AttemptCounter` unter gleichzeitigen Fehlversuchen,
  gleiche `jti` parallel, zwei PATCH auf einer Tool-Session. Kein `Clock`-Bean (118× `Instant.now()`).

### 3.5 Doku-Konsistenz

- **D-1 (mittel) Veraltete Namen nach den Umbenennungen** (rund 20 Stellen): `VERSNR` in 02:354, 06:265,
  ADR-12/19/34, glossar/abgleich; `NAME/VORNAME/GEBURTSDATUM` in 03:148, 05:322; `strasse` in 08:178,
  ADR-19:54; `matchesStammdaten`/`matchesPersonalien`/`versnrOf` in 02:227, port-vertraege:22-23/68,
  ADR-34:58; `achieved_acr` in ADR-5:34; `identification.details` im Präsens in ADR-39:21; „siehe Offen“
  in ADR-39:71; „identification audit log“ in `api/openapi.yaml`; `allowedDependencies` des Orchestrators
  in 08:114 und 03:457 veraltet; `versnr` als Ankername für `DELETE .../attributes/{attribute}` in 05:355
  (Wire-Name ist `insurance_number`).
- **D-2 (mittel) Im Code, nicht in der Doku:** `TextRef.template` (05 §Texte sagt noch „keine Antwort
  enthält Wortlaut“), Argon2id mit Umhashen und Passwortregeln, JWKS-Backoff, `jti` im Konto-Grant,
  sofort gelöschte KOBIL-Aktivierungsgeheimnisse, Regel „keine Framework-Texte in Fehlerantworten“.
- **D-3 (niedrig) Für den Ein-Stunden-Leser kürzen.** Beide Review-Dokumente (541 + 377 Zeilen) sind
  abgearbeitet und gehören nach `docs/archiv/`; dieses Dokument ersetzt sie als Stand. `docs/README.md`
  §Umsetzungsstand und der Ich-Form-Hinweis streichen; 04 und 05 tragen „früher war … jetzt“-Prosa (in die
  ADRs); 00-agent-quickstart und AGENTS.md sagen den Lesepfad zweimal; README:63 stimmt nur im Demomodus.

## 4. Reihenfolge

Grundsatz wie bisher: zuerst, was ein Experte in der ersten Stunde findet; dann, was die Aussage „10
Millionen Konten“ trägt; dann, was ein Kollege liest; dann Vereinfachung; Phase G bleibt am Ende.

**Phase H – Was ein Sicherheitsexperte zuerst findet (vor jedem weiteren Anspruch)**

1. ~~F-1 Grant auf den Orchestrator-Client beschränken; Test.~~ – erledigt 2026-09-26 (`AccountTokenGrantClients`, Keycloak-Migration V4).
2. ~~F-2 Namensvetter~~ – entschieden und umgesetzt 2026-09-26: alle drei Grundangaben Pflicht; die
   Adresse zusätzlich, wenn das Register einen Namensvetter kennt (`PersonDirectory.hasNamesake`).
   Restrisiko (Namensvetter außerhalb des Registers) in ADR-18 benannt und getragen.
3. ~~F-3 `ProductionModeCheck`~~ – erledigt 2026-09-26, mit B-2 (`FlywayResetConfig` und `demo_seed` nur
   im Demomodus) und F-7 (Kontenverwaltung hinter `@DemoSurface`, Admin-Drossel, Passwort als Hash).
4. ~~F-4 `PhoneNumber`-Wertobjekt, Drossel und Flow darüber~~ – erledigt 2026-09-26 (auch der Seed-Pfad
   `SmsCredentialPortImpl` nutzt es; vorher drei Regeln).
5. ~~F-8 QR-Code mit Pepper; F-10 Kleinigkeiten~~ – erledigt 2026-09-26: QR-Bestätigungscode als HMAC mit
   Pepper; Peer-Auth nur `typ=peer-auth+jwt` und ES256, Anker beim Anlegen = Kanal-ID; `htuMatches`
   (Pfad exakt) statt drei Kopien; E-Mail höchstens 254 Zeichen; Pepper-Mindestlänge im
   `ProductionModeCheck`; RestoreData-Geheimnis im `DeploymentTopologyCheck`. „400 statt 500“ bei
   `MgmtPasswordController` war ein Fehlbefund (schon 400). Entschieden und umgesetzt: kein Klarname in
   `findDeviceLink` vor dem Beweis; keine alten PBKDF2-Hashes zu migrieren, der PBKDF2-Pfad ist entfernt.
6. F-5/F-6 als eine Entscheidung „Schlüssel und Vertrauensanker“: Antwort-Schlüssel pinnen, ADR-9 auf
   einen Orchestrator-Schlüssel umstellen; Sperr-/Rotationspfad. Festgestellt 2026-09-26: **Ein HSM ist
   nicht geplant** – damit entfällt die Voraussetzung, unter der ein Schlüssel je Konto etwas bringt.

**Phase I – Datenbank und Betrieb (trägt „10 Millionen“)**

7. B-1 Postgres-Treiber und -Profil, Testcontainers-Migrationstest, H2-Spezifika aus den Migrationen –
   **zurückgestellt (Entscheidung 2026-09-26): H2 bleibt vorerst die einzige Datenbank.** Bis dahin gilt
   „10 Millionen Konten“ für Schema und Zugriffspfade, nicht für den Betrieb. Sofort umgesetzt wird nur,
   was davon unabhängig ist: `demo_seed` nur im Demomodus, B-2.
8. B-3 Keycloak-Probe ersetzen (Logout-Meldung beendet den Kanal), B-7 Bulk-Delete, Batches, 14 Tage.
9. B-4 Fehler-Fallback; B-5 Actuator und Kennzahlen; B-8 Jobs zählen und dokumentieren.
10. B-6 Backup/Restore in 07 mit geprobtem Restore; F-10 `kid` für den Protokoll-HMAC.

**Phase J – Was ein Kollege liest**

11. A-1 Doku-Drift Keycloak (03, 04, ADR-8/9/29/34, yml, Realm-Migration) und tote Reste im Code.
12. D-1 veraltete Namen, D-2 fehlende Beschreibungen, A-5 ADR-Widersprüche (ADR-31-Titel, ADR-5, ADR-20).
13. D-3 Review-Dokumente archivieren, README kürzen, Lesepfad einmal.

**Phase K – Vereinfachung**

14. ~~A-2 „Enrollment zuerst“ entscheiden~~ – entschieden 2026-09-26: **Das Experiment bleibt**, beide
    Reihenfolgen und der Schalter.
15. A-3 – entschieden 2026-09-26: **Die Duplikation bei den Tools ist gewollt**, nichts Generisches
    (keine Basisklassen, kein `@MappedSuperclass`, kein Dispatcher); höchstens eine Bibliothek kleiner,
    explizit importierter Hilfsfunktionen. Bleibt: tote Spalte, falscher Kommentar in
    `IdentEidToolController`, Naming. A-4 Verfügbarkeit und Flags auf einen Mechanismus.
16. A-6 Zuschnitt (`ChannelResponseAssembler`, `ClaimLedger`/`AnchorRegistry`, `EvidenceTrail`), Totes weg.
17. A-5 ADRs zusammenlegen und archivieren; A-7 Texte Stufe a.

**Phase L – Tests**

18. T-1 fehlende Tests (Step-up, Listener, Start-Checks, Reset-Schutz).
19. T-2 Retention als Datenbanktest; T-3 Scheduling im Testprofil aus, `Clock`-Bean, Parallelitätstests.

**Phase G – Frontends und Ausführungsumgebung** (unverändert aus der ersten Bewertung, Punkte 31–35)

## 5. Drei Fragen, die Code und Doku heute nicht beantworten

Ein Sicherheitsexperte wird sie stellen; die Antworten gehören in ADRs, bevor Phase H als abgeschlossen gilt:

- Wie hoch ist die Kollisionsrate von Name, Vorname und Geburtsdatum im Bestand, und wer trägt das
  Restrisiko aus F-2, Betreiber oder Register?
- Welche Schlüsselverwaltung ist das Ziel (KMS/HSM, Rotation, Sperre) für `node_signing_key`,
  `keycloak_keypair` und den Komponentenschlüssel in Keycloak, die heute im Klartext in Datenbanken
  liegen, mit H2-Konsole und `/admin` als Lesepfad?
- Wertet die anfragende Anwendung den `acr`-Claim tatsächlich gegen ihre Anforderung aus? Nach einem
  abgebrochenen Step-up bleibt der Kanal angemeldet und liefert Tokens mit dem alten Niveau. Das ist
  korrekt, aber nur, wenn die Anwendung es prüft.
