# Idee: Keycloakify statt FreeMarker für Anmeldung, Registrierung und Verwaltung der Verfahren

Status: **Konzept, nicht umgesetzt** (Issue `DPoP-demo-an4z`). Die Verwaltung der Anmeldeverfahren im
Web-Kanal läuft bereits als Required Action von Keycloak mit FreeMarker (`orchestrator-manage-methods.ftl`,
[API](../05-api.md), „Anmeldeverfahren verwalten im Web-Kanal“). Keycloakify würde nur deren Darstellung
ersetzen, wie die aller anderen Seiten.

---

## 1) Ausgangslage

Anmeldung, Registrierung und die Verwaltung der Verfahren im Web-Kanal zeigt Keycloak heute
vollständig über von Hand geschriebene FreeMarker-Vorlagen an
(`keycloak-extension/src/main/resources/theme/orchestrator/login/`): `orchestrator-tool.ftl` als
allgemeines Grundgerüst, die Seiten des Orchestrators (`orchestrator-select.ftl`,
`orchestrator-confirm.ftl`, `orchestrator-error.ftl`, `orchestrator-manage-methods.ftl`), eine Vorlage
je Tool (`tool-ident-*.ftl`, `tool-sms-*.ftl`, `tool-email-*.ftl`, `tool-password-*.ftl`,
`tool-qr-*.ftl`) und die gemeinsame Auswahl der Testperson `demo-person-picker.ftl`. Die Frage:
Lässt sich das durch React-Komponenten mit [Keycloakify](https://www.keycloakify.dev/) ersetzen, und
eignet sich Keycloakify überhaupt für die Verwaltung der Verfahren?

## 2) Was in Keycloak tatsächlich passiert

Der Web-Kanal ist mehr als ein Theme. Code in Keycloak, der tief über dessen Schnittstellen für
Erweiterungen (SPI) eingebunden ist, entscheidet, *welche* Vorlage mit *welchen* Werten aus
`stepData` angezeigt wird: `OrchestratorAuthenticator`, `OrchestratorUpdateAuthenticator`,
`OrchestratorResumeAuthenticator`, `WebToolRendererSpi` (mit einer Renderer-Factory je Tool),
`OrchestratorStorageProvider`, `OrchestratorAcrAmrMapper`, `PeerAuthAssertionSigner` und
weitere (`keycloak-extension/src/main/java/com/example/dpop/kcext/`). **Keycloakify würde nur die
Darstellung ersetzen** (FreeMarker durch ein React-Bundle), nicht diese Logik; der Java-Code bliebe
unverändert. Das wäre eine klar abgegrenzte Änderung mit geringem Risiko.

## 3) Warum Keycloakify zu diesem Projekt passt

- Keycloakify unterstützt ausdrücklich **ganz eigene Seiten**, nicht nur die Standardseiten von
  Keycloak wie `login.ftl` oder `register.ftl`. Dafür gibt es eine Datei je Seite mit einer
  `pageId`, die genau dem bisherigen Namen der FTL-Datei entspricht (z. B. `tool-sms-auth.ftl` →
  `tool-sms-auth.tsx`), und `kcContextExtensionPerPage` für zusätzliche Werte. Das entspricht genau
  dem, was `WebToolRenderer` heute tut: je Tool ein `createForm("tool-xxx.ftl")` mit
  `setAttribute(...)`.
- Seiten, die nicht ersetzt werden, fallen von selbst auf das Standard-Theme von Keycloak zurück.
  Man kann also schrittweise umstellen.
- Der Browser spricht weiterhin **nur mit Keycloak**. An der Grundregel „Der Browser spricht nie mit
  dem Orchestrator“ ([12-entscheidungen.md](../12-entscheidungen.md) ADR-8) ändert sich nichts; nur
  die Technik der Darstellung wechselt.
- Es passt zur bestehenden Regel, `stepData` unverändert durchzureichen und nicht vorab einzelne
  Werte herauszuziehen: Das allgemeine Grundgerüst `orchestrator-tool.ftl` (ein Eingabefeld je
  Schlüssel in `stepData`) lässt sich direkt als allgemeine React-Komponente nachbauen, die immer
  dann greift, wenn es für ein Tool keine eigene Seite gibt.

## 4) Wo es in Keycloak eingebunden wird

- Keycloakify baut ein Theme (als JAR oder als entpacktes Verzeichnis `theme/`), das Keycloak wie
  jedes andere Theme lädt. Es ersetzt nur den Inhalt von
  `keycloak-extension/src/main/resources/theme/orchestrator/login/`. Die Konfiguration des Realms
  (`login_theme=orchestrator`, gesetzt über `keycloak-migrations/*.kc.kts`) bleibt unverändert,
  ebenso alle Java-Erweiterungen.
- Es braucht ein neues npm-Paket, weil es derzeit **keinen** npm-Workspace gibt: `frontend/` ist das
  einzige JavaScript-Paket, und es gibt kein `package.json` im obersten Verzeichnis (siehe
  `settings.gradle.kts`, dort stehen nur Gradle-Teilprojekte). Empfehlung: ein eigenständiges Paket,
  z. B. `keycloak-theme/`, und **nicht** in die bestehende Demo-App in `frontend/` einbauen. Die
  Zielgruppe ist eine andere (Theme für Keycloak statt Demo-Oberfläche), und es hat einen eigenen
  Ablauf für Build und Storybook.
- Die Einbindung in den Build folgt dem bestehenden Muster: Die `build.gradle.kts` im obersten
  Verzeichnis hat bereits die Tasks `npmInstall` und `npmBuild` für `frontend/` sowie
  `stageKeycloakArtifact`, das die Dateien des Themes nach `build/podman/keycloak/theme/` kopiert.
  Für `keycloak-theme/` ergänzt man dieselbe Kette.
- Mit dem Java-Build `shadowJar` der `keycloak-extension` hat das nichts zu tun. Das Theme und das
  JAR mit den Erweiterungen sind unabhängige Ergebnisse, die nur im selben Container-Image landen.

## 5) Verwaltung der Verfahren: drei Möglichkeiten, noch keine Entscheidung

Für die Verwaltung der Verfahren selbst (den Mechanismus im Orchestrator beschreibt [API](../05-api.md),
„Anmeldeverfahren verwalten im Web-Kanal“) gibt es bei der Darstellung drei Möglichkeiten:

- **(A) Derselbe Mechanismus wie bei der Anmeldung, nur eine andere Darstellung.** Keycloakify zeigt
  die Seiten der Required Action genauso an wie die Seiten der Anmeldung, über denselben
  Mechanismus mit `WebToolRenderer` und eigenen Seiten. Das empfiehlt sich als Anfang: Die
  Architektur ändert sich nicht, und es folgt direkt aus den Abschnitten 3 und 4.
- **(B) Das eigene Account-Theme von Keycloak mit dessen Account-REST-API.** Vermutlich nicht
  möglich: Die Account-REST-API von Keycloak kann Credentials, die Keycloak selbst nicht kennt
  (E-Mail, das Passwort im Orchestrator, SMS, QR), nicht anlegen oder ändern, sondern nur löschen.
  Außerdem würde die Zuständigkeit für Journey und `AuthPolicy` teilweise vom Orchestrator zu Keycloak
  wandern. Das widerspricht der bisherigen Entscheidung, dass allein der Orchestrator die Verfahren
  verwaltet.
- **(C) Eine eigene Oberfläche mit eigenem Backend** (ein „Backend for Frontend“), das direkt von
  Server zu Server mit dem Orchestrator spricht. Im Aufbau entspricht das dem, was
  `OrchestratorAuthenticator` innerhalb von Keycloak schon tut (Assertion zwischen den Servern statt
  DPoP). Die Grundregel „Der Browser spricht nie mit dem Orchestrator“ bleibt dabei **gewahrt**, weil
  der Browser nur mit dem eigenen Backend spricht. Es entsteht aber eine neue Vertrauensgrenze, ähnlich
  der, die Keycloak heute selbst hat. Dafür spricht: Die Oberfläche der Verwaltung ist ganz von den
  Themes und Sitzungen in Keycloak getrennt und lässt sich unabhängig ausliefern. Dagegen spricht: eine
  neue Serverkomponente und neuer Aufwand für Betrieb und Sicherheit, nur für eine Funktion zur
  Selbstverwaltung.

| | Aufwand | Neue Vertrauensgrenze | Passt zur Architektur | Blockiert durch |
|---|---|---|---|---|
| A: in Keycloak (Required Action) | niedrig | nein | gut | — |
| B: Account-Console/REST | mittel bis hoch | nein (nutzt Keycloak) | schlecht | fehlende API für eigene Credentials |
| C: eigene Oberfläche mit eigenem Backend | hoch | ja (neues Backend) | mittel | — |

## 6) Nächste Schritte (falls es umgesetzt wird)

1. Das Paket `keycloak-theme/` anlegen (Grundgerüst über die Kommandozeile von Keycloakify, React und
   TypeScript, dazu Vitest und Storybook wie im übrigen Projekt).
2. Die bestehenden FTL-Dateien durchgehen: Welche `pageId`s brauchen die eigenen Seiten, und welche
   Werte aus `stepData` werden gebraucht? (Jede FTL-Datei wird zu genau einer Seitenkomponente von
   Keycloakify.)
3. Zuerst die allgemeine Komponente bauen, die `orchestrator-tool.ftl` entspricht, und dann Tool für
   Tool eigene Seiten ergänzen, also schrittweise umstellen.
4. Die Tasks in Gradle (`npmInstall`, `npmBuild`, `stageKeycloakArtifact`) nach dem Vorbild von
   `frontend/` ergänzen.
5. Von Anfang bis Ende gegen ein echtes Keycloak testen (Podman Compose).
