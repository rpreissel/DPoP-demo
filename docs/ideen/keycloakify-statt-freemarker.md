# Idee: Keycloakify neben FreeMarker für Anmeldung, Registrierung und Verwaltung der Verfahren

Status: **weitgehend umgesetzt** (Issue `DPoP-demo-an4z`): gemeinsames Aussehen, Keycloakify-Theme mit
allen Seiten des Orchestrators, Build und Laufzeit-Schalter stehen; es fehlen die Ende-zu-Ende-Tests. Die Verwaltung der Anmeldeverfahren im
Web-Kanal läuft bereits als Required Action von Keycloak mit FreeMarker (`orchestrator-manage-methods.ftl`,
[API](../05-api.md), „Anmeldeverfahren verwalten im Web-Kanal“). Keycloakify würde nur deren Darstellung
ersetzen, wie die aller anderen Seiten.

Kurz gesagt: Keycloakify **ersetzt** FreeMarker nicht, sondern läuft **daneben**. Beide Themes liegen
immer in Keycloak. Welches die Anmeldeseiten zeigt, entscheidet ein Schalter im Orchestrator, den man
zur Laufzeit umlegen kann. Beide Themes sehen gleich aus und bekommen ein gemeinsames, schlichtes Aussehen ohne Logo.

---

## 1) Ausgangslage

Anmeldung, Registrierung und die Verwaltung der Verfahren im Web-Kanal zeigt Keycloak heute
vollständig über von Hand geschriebene FreeMarker-Vorlagen an
(`keycloak-extension/src/main/resources/theme/orchestrator/login/`):

- **Grundgerüst:** `orchestrator-tool.ftl`, die allgemeine Seite, wenn ein Tool keine eigene hat.
- **Seiten des Orchestrators:** `orchestrator-select.ftl`, `orchestrator-confirm.ftl`,
  `orchestrator-error.ftl`, `orchestrator-manage-methods.ftl`.
- **Eine Vorlage je Tool:** `tool-ident-{eid,fsc,kvnr}.ftl`, `tool-sms-{auth,enroll,lookup}.ftl`,
  `tool-email-{auth,enroll,lookup}.ftl`, `tool-password-{auth,enroll,lookup}.ftl`,
  `tool-qr-{enroll,wait}.ftl`.
- **Gemeinsam genutzt:** die Auswahl der Testperson `demo-person-picker.ftl`.
- **Aussehen:** `resources/css/orchestrator.css` auf dem geerbten Theme `keycloak` (PatternFly).

Die Frage: Lässt sich das durch React-Komponenten mit [Keycloakify](https://www.keycloakify.dev/)
darstellen, ohne FreeMarker aufzugeben, und eignet sich Keycloakify für die Verwaltung der Verfahren?

## 2) Was in Keycloak tatsächlich passiert

Der Web-Kanal ist mehr als ein Theme. Code in Keycloak, der tief über dessen Schnittstellen für
Erweiterungen (SPI) eingebunden ist, entscheidet, *welche* Vorlage mit *welchen* Werten aus
`stepData` angezeigt wird: `OrchestratorAuthenticator`, `OrchestratorUpdateAuthenticator`,
`OrchestratorResumeAuthenticator`, `WebToolRendererSpi` (mit einer Renderer-Factory je Tool),
`OrchestratorStorageProvider`, `OrchestratorAcrAmrMapper`, `PeerAuthAssertionSigner` und
weitere (`keycloak-extension/src/main/java/com/example/dpop/kcext/`). Alle Formulare entstehen an
einer Stelle, in `WebFormRenderer`. **Keycloakify würde nur die Darstellung ersetzen** (FreeMarker
durch ein React-Bundle), nicht diese Logik; der Java-Code bliebe unverändert.

## 3) Warum Keycloakify zu diesem Projekt passt

- Keycloakify unterstützt ausdrücklich **ganz eigene Seiten**, nicht nur die Standardseiten von
  Keycloak wie `login.ftl` oder `register.ftl`. Dafür gibt es eine Datei je Seite mit einer
  `pageId`, die genau dem bisherigen Namen der FTL-Datei entspricht (z. B. `tool-sms-auth.ftl` →
  `tool-sms-auth.tsx`), und `kcContextExtensionPerPage` für zusätzliche Werte. Das entspricht genau
  dem, was `WebToolRenderer` heute tut: je Tool ein `createForm("tool-xxx.ftl")` mit
  `setAttribute(...)`.
- Der Browser spricht weiterhin **nur mit Keycloak**. An der Grundregel „Der Browser spricht nie mit
  dem Orchestrator“ ([12-entscheidungen.md](../12-entscheidungen.md) ADR-8) ändert sich nichts; nur
  die Technik der Darstellung wechselt.
- Es passt zur bestehenden Regel, `stepData` unverändert durchzureichen und nicht vorab einzelne
  Werte herauszuziehen: Das Grundgerüst `orchestrator-tool.ftl` (ein Eingabefeld je Eintrag in
  `stepData.missingFields`) lässt sich direkt als allgemeine React-Komponente nachbauen.

## 4) Parallelbetrieb: realmweit umschalten

- **Zwei Themes nebeneinander:** `orchestrator` (FreeMarker, wie heute) und
  `orchestrator-keycloakify` (React). Beide stecken immer im Keycloak-Image; der Name ist neutral und
  trägt keinen Markennamen.
- **Das FreeMarker-Theme ist das Eltern-Theme des Keycloakify-Themes** (`parent=orchestrator` statt
  `parent=keycloak`, im ersten Versuch gegen Keycloak 26.6 geprüft). Jede Seite, für die es noch
  keine React-Komponente gibt, zeigt Keycloak deshalb mit der FreeMarker-Vorlage, im selben Aussehen.
  So lässt sich Seite für Seite umstellen, ohne dass eine Seite fehlt.
- **Umgeschaltet wird das Login-Theme des Realms** über die Admin-API von Keycloak:
  `PUT /admin/realms/{realm}` mit nur `{ "loginTheme": "…" }`. Keycloak übernimmt dabei nur die
  gesetzten Felder, der Rest des Realms bleibt, wie er ist.
- **Alles wechselt mit:** alle Clients (auch `dpop-demo-web-qr-test`), der Step-up und die Required
  Action `orchestrator-manage-methods`. Es braucht keinen zweiten Client und keine Änderung am
  OIDC-Ablauf im Frontend.
- **Wirkung sofort:** Die nächste Seite, die Keycloak anzeigt, kommt aus dem anderen Theme, auch
  mitten in einer laufenden Anmeldung. Das ist vertretbar, weil beide Themes denselben Seitenvertrag
  erfüllen (Abschnitt 6).
- **Keine neuen Rechte:** Der Orchestrator schaltet mit dem **Migrations-Client**
  `orchestrator-migration` (Master-Realm, `private_key_jwt`, angelegt von
  `MigrationClientBootstrapFactory`), der das Realm ohnehin aufbaut und ändern darf. Das Token kommt
  aus `KeycloakMigrationToken.accessToken()`, der Admin-Client aus `buildAdminClient(...)`
  (`keycloak-migrations`, `AdminClient.kt`), genau wie in `KeycloakMigrationRunnerStartup`. Der
  Service-Account `orchestrator-admin` behält seine Rechte (manage-users, view-realm).
- **Kein neuer Code in der Extension**, keine eigene ThemeSelector-SPI.

## 5) Der Schalter im Orchestrator

Er folgt dem Vorbild „Enrollment zuerst“ (`RegistrationOrderController`, `FeatureFlagService`):

- **Speicher:** ein Laufzeit-Schalter `FeatureFlags.KEYCLOAK_LOGIN_KEYCLOAKIFY =
  "keycloak-login-keycloakify"` im bestehenden `FeatureFlagService`. Keine Zeile in der Datenbank
  heißt aus, also FreeMarker. **Der Orchestrator ist die Quelle der Wahrheit**, Keycloak wird
  nachgezogen.
- **Endpunkt:** ein eigener, benannter Endpunkt `$ADMIN_API/login-theme` mit GET und PUT
  `{ "theme": "freemarker" | "keycloakify" }`, hinter dem Admin-Login wie alle Schalter. PUT setzt
  zuerst das Theme im Realm (neue Komponente `KeycloakLoginTheme` im Paket `orchestrator/kc`, nur im
  Spring-Profil `keycloak`) und erst dann den Schalter. Lehnt Keycloak ab, bleibt der Schalter, wie er
  war, und die Admin-Seite zeigt den Fehler.
- **Abgleich beim Start:** Nach den Keycloak-Migrationen setzt der Orchestrator das Theme im Realm
  einmal auf den Stand des Schalters. Die Migration kennt nur den Anfangswert
  (`loginTheme: orchestrator` in `src/main/resources/application-keycloak.yml`, von
  `V1__realm.kc.kts` durchgereicht); ein neu aufgebautes Realm fällt so nicht still auf FreeMarker
  zurück.
- **Admin-Seite:** `AdminLoginThemeView.tsx` nach Vorbild von `AdminRegistrationOrderView.tsx`.
- **Willkommensseite:** `ServerInfo` meldet zusätzlich, welches Theme gerade aktiv ist.
- **Demo zurücksetzen** (`AdminAccountsController.reset`) schaltet zurück auf FreeMarker.

## 6) Was beide Themes teilen müssen

- **Texte auf dem Server:** `KcTexts` liest die Texte aus den Messages des *gerade aktiven*
  Login-Themes. Keycloak führt die Messages entlang der Eltern-Themes zusammen; das Keycloakify-Theme
  erbt sie also vom FreeMarker-Theme, ohne Kopie.
- **Texte im Browser:** Die Vorlagen rufen `${t.of("…")}` auf, `t` ist `KcTexts.TemplateTexts`. Im
  `kcContext` kommt davon nur ein leeres Objekt an, und Keycloakify bringt nur seine eigenen
  Message-Schlüssel mit, nicht unsere. Deshalb setzt `WebFormRenderer` neben `t` ein zweites
  Attribut `texts`: die Texte, die die React-Seite braucht, als einfache Map (Schlüssel → Text in der
  Sprache der Anmeldung).
  - **Nur die der Seite:** Der Theme-Build (`keycloak-theme/scripts/texts-per-page.mjs`) sammelt je
    Seite alle `t("…")`-Vorlagen der Komponente und ihrer Importe und schreibt sie als
    `orchestratorTexts.<pageId>=id,id,…` in die `theme.properties`. `KcTexts.forBrowser` liest diesen
    Eintrag aus dem aktiven Theme und schickt nur diese Texte, bei der Auswahlseite z. B. 4 statt 83.
    Das FreeMarker-Theme hat keinen solchen Eintrag und bekommt keine.
  - **Der Seitenname vor dem Rendern:** Jede Tool-Factory nennt ihre Seite über
    `WebToolRendererFactory.template()`.
  - **Live:** Ändert `/translate-texts` einen Text, zeigt ihn das Theme ohne neuen Build. Neu bauen
    muss man es nur, wenn eine React-Seite eine neue Vorlage bekommt.
  - `keycloak-theme/src/texts.ts` bildet den Schlüssel wie `KcText.idOf` (synchrones SHA-256 wie
    im Frontend).
- **`/translate-texts`:** `npm run texts:export` im Theme schreibt alle `t("…")`-Vorlagen samt
  Fundstellen nach `keycloak-theme/build/texts-catalog.json`; `KcTextCatalog` der Extension liest den
  Katalog neben den `.ftl`-Vorlagen. Beide Themes teilen damit das Bundle `keycloak`, und
  `KcTextCatalogTest` meldet eine neue Vorlage im React-Code wie eine in einer `.ftl`-Datei.
- **Seitenvertrag:** Jede Seite bekommt dieselben Attribute, egal welches Theme sie zeigt. Neben `t`:
  - `orchestrator-select`: `title`, `description`, `options`, `optionLabels`, `offerRegistration`
  - `orchestrator-tool`: `toolId`, `fields` (aus `stepData.missingFields`)
  - `orchestrator-confirm`: `title`, `confirmLabel`, `cancelLabel`
  - `orchestrator-error`: nur die Fehlermeldung
  - `orchestrator-manage-methods`: `methods` (je Eintrag `id`, `method`, `label`), Hinweis als Info
  - jede `tool-*`-Seite: `toolId`, `title`, `hint`, dazu je Tool:
    - `tool-password-auth`, `tool-password-enroll`: `demoPassword`
    - `tool-password-lookup`: `demoEmail`, `demoPassword`, `demoPersonsJson`
    - `tool-email-auth`, `tool-sms-auth`: `demoTan`
    - `tool-email-enroll`, `tool-email-lookup`: `step`, `demoEmail`, `demoTan`, `demoPersonsJson`
    - `tool-sms-enroll`: `step`, `demoTan`
    - `tool-sms-lookup`: `step`, `demoEmail`, `demoTan`, `demoPersonsJson`
    - `tool-ident-eid`: `step`, `demoPersonsJson`
    - `tool-ident-fsc`: `personalienPage`, `demoPersonsJson`
    - `tool-ident-kvnr`: `demoPersonsJson`
    - `tool-qr-enroll`: keine
    - `tool-qr-wait`: `pairingCode`, `verificationCode`, `deepLink`, `qrDataUri`

  `demoPersonsJson` ist roher JSON-Text. Die Liste ist aus `WebFormRenderer` und den
  `*RendererFactory`-Klassen abgelesen; ändert sich dort etwas, gilt es für beide Themes.
- **Skripte:** `demo-person-picker.ftl` und `tool-qr-wait.ftl` enthalten eigenes JavaScript; in
  Keycloakify werden daraus React-Komponenten.
- **Aussehen:** dieselben Design-Tokens aus einer Datei (Abschnitt 7).

## 7) Gemeinsames Aussehen für beide Themes

Kein Logo und kein Markenname, nur Farben, Formen und Schrift.

- **Farben:** warme Grautöne statt Markenfarbe.
  - Fläche `#eceae8`, Karten weiß, zarte Fläche `#f5f2ef`
  - Schrift `#454542`, Überschriften `#333332`, Rahmen `#a09f9e`
  - Hauptknopf dunkelgrau `#454542` mit heller Schrift, beim Überfahren `#2f2f2d`
  - Blau nur für den Fokusrahmen (`#0a7dc2`), damit die Tastaturbedienung sichtbar bleibt
  - unten ein dunkles Band `#454542` und eine hellgraue Fußzeile `#ececec`, beide leer
- **Formen:** eckige Kanten überall, keine Schatten. Der Hauptknopf trägt einen dünnen Pfeil nach
  rechts; die Auswahl des Verfahrens ist eine Liste von Zeilen mit Pfeil statt eines Stapels Knöpfe.
- **Schrift:** die serifenlose Systemschrift mit Arial/Helvetica als Ersatz, keine eingebundenen
  Schriftdateien; Grundgröße 18 px, Überschriften groß und dünn (45 px für den Namen des Realms,
  28 px für den Seitentitel), Beschriftungen der Felder fett.
- **Aufbau:** oben eine leere weiße Leiste, darunter auf grauer Fläche der Name des Realms als große
  Überschrift, dann eine weiße Karte mit 720 px Breite für das Formular. Knöpfe stehen rechts, der
  Hauptknopf ganz außen. Anrede mit „Sie“, wie die bestehenden Texte.
- **Eine Quelle:** die Tokens als CSS-Custom-Properties in
  `theme/orchestrator/login/resources/css/tokens.css`. FreeMarker bindet sie über
  `theme.properties` (`styles=`) ein, `orchestrator.css` überschreibt damit die PatternFly-Optik;
  Keycloakify importiert dieselbe Datei.

Die FreeMarker-Seiten bekommen dieses Aussehen **zuerst**, unabhängig von Keycloakify. So lassen sich
beide Themes später direkt nebeneinander vergleichen.

## 8) Wo es gebaut und eingebunden wird

- **Eigenes npm-Paket `keycloak-theme/`**, nicht in `frontend/`: Es gibt keinen npm-Workspace und
  kein `package.json` im obersten Verzeichnis. Die Zielgruppe ist eine andere (Theme für Keycloak statt
  Demo-Oberfläche). Gleiche Werkzeuge wie `frontend/`: React 19, Vite, TypeScript, Vitest.
- **Ausgabe als JAR:** `keycloakify build` (braucht Maven) erzeugt
  `dist_keycloak/orchestrator-keycloakify-theme.jar`, nur für Keycloak 26. Das JAR gehört nach
  `/opt/keycloak/providers/`; Keycloak findet das Theme darin selbst.
- **Gradle:** nach dem Muster von `npmInstall`/`npmBuild` für `frontend/` eigene Tasks für
  `keycloak-theme/`; `stageKeycloakArtifact` kopiert das JAR zusätzlich nach `build/podman/keycloak/`,
  das Keycloak-`Dockerfile` bekommt ein zweites `COPY` nach `providers/`.
- **Theme im JAR:** Das FreeMarker-Theme liegt heute auch im Shadow-JAR der `keycloak-extension`, und
  `exportTexts`/`KcTextCatalog` lesen seine `.ftl`-Dateien. Daran ändert sich nichts; das
  Keycloakify-Theme gehört nicht ins JAR.

## 9) Verwaltung der Verfahren: drei Möglichkeiten

Für die Verwaltung der Verfahren selbst (den Mechanismus im Orchestrator beschreibt [API](../05-api.md),
„Anmeldeverfahren verwalten im Web-Kanal“) gibt es bei der Darstellung drei Möglichkeiten:

- **(A) Derselbe Mechanismus wie bei der Anmeldung, nur eine andere Darstellung. Empfohlen.**
  Keycloakify zeigt die Seiten der Required Action genauso an wie die Seiten der Anmeldung. Die
  Architektur ändert sich nicht, der Schalter aus Abschnitt 5 gilt automatisch mit.
  - Aufwand: niedrig
  - neue Vertrauensgrenze: nein
- **(B) Das eigene Account-Theme von Keycloak mit dessen Account-REST-API.** Vermutlich nicht
  möglich: Die Account-REST-API kann Credentials, die Keycloak selbst nicht kennt (E-Mail, das
  Passwort im Orchestrator, SMS, QR), nicht anlegen oder ändern, sondern nur löschen. Außerdem würde
  die Zuständigkeit für Journey und `AuthPolicy` teilweise vom Orchestrator zu Keycloak wandern.
  - Aufwand: mittel bis hoch
  - passt schlecht zur Entscheidung, dass allein der Orchestrator die Verfahren verwaltet
- **(C) Eine eigene Oberfläche mit eigenem Backend** („Backend for Frontend“), das von Server zu
  Server mit dem Orchestrator spricht, so wie `OrchestratorAuthenticator` innerhalb von Keycloak. Die
  Grundregel „Der Browser spricht nie mit dem Orchestrator“ bleibt gewahrt.
  - Aufwand: hoch
  - neue Vertrauensgrenze: ja, eine neue Serverkomponente mit eigenem Betrieb und eigener Sicherheit,
    nur für die Selbstverwaltung

## 10) Schritte

1. **FreeMarker im neuen Aussehen (erledigt):** `tokens.css` anlegen, `orchestrator.css` darauf umstellen. Geht
   sofort und unabhängig von allem anderen.
2. **Erster Versuch mit Keycloakify (erledigt):** `keycloak-theme/` mit `orchestrator-select` und
   `orchestrator-tool` als React-Seiten; alle anderen Seiten kommen vom FreeMarker-Eltern-Theme.
   Gegen Keycloak geprüft: Auswahl (React), Passwortseite (FreeMarker), Fehlermeldung von `KcTexts`,
   Texte auf Deutsch und Englisch aus `kcContext.texts`.
3. **Schalter im Orchestrator (erledigt):** `KeycloakLoginTheme` über den Migrations-Client,
   Endpunkt, Abgleich beim Start, Admin-Seite, `ServerInfo`, Demo-Reset. Voreinstellung FreeMarker.
4. **Restliche allgemeine Seiten (erledigt):** `orchestrator-confirm` und `orchestrator-error`. Jede
   `pageId` ohne React-Komponente zeigt weiter die FreeMarker-Vorlage.
5. **Tool für Tool (erledigt):** alle 14 Tool-Seiten und `orchestrator-manage-methods` als
   React-Seiten, gemeinsame Bausteine in `keycloak-theme/src/login/components/`. Gegen Keycloak
   geprüft: SMS-Anmeldung bis zur Code-Eingabe, QR-Warteseite.
6. **Build (erledigt):** Gradle-Tasks, `stageKeycloakArtifact`, `Dockerfile` (Abschnitt 8).
7. **Ende-zu-Ende-Tests** mit Playwright gegen ein echtes Keycloak (Podman Compose): dieselben
   Abläufe mit beiden Themes, dazu Umschalten zur Laufzeit ohne Neustart und der Abgleich nach einem
   Neustart.
