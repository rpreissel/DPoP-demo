# Idee: Keycloakify statt FreeMarker für Login/Registrierung/Methodenverwaltung

Status: **Konzept, nicht umgesetzt**. Zurückgestellt bis
[Anmeldeverfahren im Web-Kanal verwalten](manage-auth-methods-im-web-kanal.md) umgesetzt ist —
die dortige Required-Action-Lösung ist die Grundlage, auf der eine spätere Keycloakify-Migration
für die Methodenverwaltung aufsetzen würde.

---

## 1) Ausgangslage

Login, Registrierung und (perspektivisch) Methodenverwaltung im Web-Kanal werden heute komplett
über handgeschriebene FreeMarker-Templates gerendert
(`keycloak-extension/src/main/resources/theme/orchestrator/login/`: `theme.properties`,
`orchestrator-tool.ftl` als generischer Scaffold, plus Templates pro Tool wie `tool-sms-auth.ftl`,
`tool-email-*.ftl`, `tool-password-*.ftl`, `demo-person-picker.ftl`). Frage: Lässt sich das durch
React-Komponenten via [Keycloakify](https://www.keycloakify.dev/) ersetzen, und ist Keycloakify
für die Methodenverwaltung überhaupt geeignet?

## 2) Was Keycloak-seitig tatsächlich passiert

Der Web-Kanal ist nicht nur Theming — tiefe Keycloak-SPI-Logik steuert, *welches* Template mit
*welchen* `stepData`-Attributen gerendert wird: `OrchestratorAuthenticator`,
`OrchestratorUpdateAuthenticator`, `OrchestratorResumeAuthenticator`, `WebToolRendererSpi` (+
Renderer-Factories pro Tool), `OrchestratorPasswordStorageProvider`, `OrchestratorAcrAmrMapper`,
`PeerAuthAssertionSigner` u. a. (`keycloak-extension/src/main/java/com/example/dpop/kcext/`).
**Keycloakify würde nur die Rendering-Schicht ersetzen** (FreeMarker → React-Bundle), nicht diese
SPI-Logik — der Java-Code bliebe unverändert. Eine sauber abgegrenzte, risikoarme Änderung.

## 3) Warum Keycloakify für dieses Projekt passt

- Keycloakify unterstützt explizit **vollständig eigene, nicht-standardmäßige Pages** (nicht nur
  die Stock-Keycloak-Seiten wie `login.ftl`/`register.ftl`): eine Page-Datei mit `pageId`, der
  exakt dem bisherigen FTL-Dateinamen entspricht (z. B. `tool-sms-auth.ftl` → `tool-sms-auth.tsx`),
  plus `kcContextExtensionPerPage` für zusätzliche Attribute. Das bildet genau ab, was
  `WebToolRenderer` heute tut: pro Tool ein `createForm("tool-xxx.ftl")` mit `setAttribute(...)`.
- Seiten, die nicht überschrieben werden, fallen automatisch auf Keycloaks Standard-Theme zurück —
  erlaubt inkrementelle Migration.
- Der Browser spricht weiterhin **nur mit Keycloak** — keine Änderung an der Leitplanke
  "Browser spricht nie mit dem Orchestrator" ([web-keycloak-kanal.md](web-keycloak-kanal.md) §1/
  §13). Nur das Rendering-Backend wechselt.
- Passt zur bestehenden Regel "rohes `stepData` durchreichen, nicht vorab extrahieren": der
  generische `orchestrator-tool.ftl`-Scaffold (ein Input pro `stepData`-Key) lässt sich 1:1 als
  generische React-Fallback-Komponente nachbauen, mit gezielten Overrides pro Tool.

## 4) Integrationspunkt in Keycloak

- Keycloakify baut ein Theme (JAR oder entpacktes `theme/`-Verzeichnis), das Keycloak wie jedes
  andere Theme lädt. Es ersetzt ausschließlich den Inhalt von
  `keycloak-extension/src/main/resources/theme/orchestrator/login/` — die Realm-Konfiguration
  (`login_theme=orchestrator`, über `keycloak-migrations/*.kc.kts` gesetzt) bleibt unverändert,
  ebenso alle Java-SPI-Provider.
- Neues npm-Paket nötig, da aktuell **kein** npm-Workspace existiert (`frontend/` ist das einzige
  JS-Paket, kein Root-`package.json`, siehe `settings.gradle.kts` — nur Gradle-Submodule).
  Empfehlung: eigenständiges Paket, z. B. `keycloak-theme/`, **nicht** in die bestehende
  `frontend/`-Debug-Dashboard-SPA integriert — andere Zielgruppe (Keycloak-Theme-Build vs.
  Demo-SPA), eigener Build-/Storybook-Zyklus.
- Build-Wiring analog zum bestehenden Muster: Root `build.gradle.kts` hat bereits
  `npmInstall`/`npmBuild`-Exec-Tasks für `frontend/` sowie `stageKeycloakArtifact`, das
  Theme-Dateien nach `build/podman/keycloak/theme/` kopiert. Für `keycloak-theme/` dieselbe Kette
  ergänzen.
- Kein Zusammenhang mit dem Java-`shadowJar`-Build der `keycloak-extension` — Theme-Artefakt und
  SPI-Provider-Jar sind unabhängige Artefakte, die nur im selben Container-Image landen.

## 5) Methodenverwaltung: drei Optionen, keine Vorentscheidung

Für die Methodenverwaltung selbst (siehe
[manage-auth-methods-im-web-kanal.md](manage-auth-methods-im-web-kanal.md) für den orchestrator-
seitigen Mechanismus) gibt es beim Rendering drei Optionen:

- **(A) Gleicher Mechanismus wie Login, nur andere Rendering-Schicht** — Keycloakify rendert die
  Required-Action-Seiten genauso wie die Login-Seiten, über denselben
  `WebToolRenderer`/Custom-Page-Mechanismus. Empfohlen als Startpunkt: keine Architekturänderung,
  folgt direkt aus Abschnitt 3/4.
- **(B) Keycloak natives Account-Theme + Account-REST-API.** Vermutlich blockiert: Keycloaks
  Account-REST-API bietet keine Möglichkeit, nicht-standardmäßige Credential-Typen (E-Mail,
  Passwort-Backend des Orchestrators, SMS, QR) anzulegen/zu aktualisieren, nur zu löschen. Würde
  außerdem Journey-/`AuthPolicy`-Eigentümerschaft teilweise von Orchestrator zu Keycloak
  verschieben — widerspricht der bisherigen Entscheidung, dass der Orchestrator alleiniger
  Eigentümer der Methodenverwaltung bleibt.
- **(C) Externe UI mit eigenem Backend** (BFF), das server-zu-server mit dem Orchestrator spricht
  — strukturell analog zu dem, was `OrchestratorAuthenticator` innerhalb von Keycloak bereits tut
  (Peer-Auth-Assertion statt DPoP). Verletzt **nicht** die "Browser spricht nie mit dem
  Orchestrator"-Leitplanke, da der Browser nur mit dem eigenen BFF spricht — erfordert aber eine
  neue Peer-Auth-Vertrauensgrenze vergleichbar mit der, die Keycloak heute selbst hat. Pro:
  entkoppelt die Verwaltungs-UI komplett von Keycloaks Theme-/Session-Mechanik, eigener
  Deploy-Zyklus. Contra: neue Server-Komponente, neuer Betriebs-/Sicherheitsaufwand für ein reines
  Selbstbedienungs-Feature.

| | Aufwand | Neue Vertrauensgrenze | Architektur-Konsistenz | Blockiert durch |
|---|---|---|---|---|
| A: In-Keycloak (Required Action) | niedrig | nein | hoch | — |
| B: Account-Console/REST | mittel–hoch | nein (nutzt Keycloak) | niedrig | fehlende Custom-Credential-API |
| C: Externe UI + eigenes BFF | hoch | ja (neues BFF) | mittel | — |

## 6) Nächste Schritte (falls umgesetzt)

1. `keycloak-theme/`-Paket anlegen (Keycloakify-CLI-Scaffold, React + TypeScript, Vitest/
   Storybook wie im restlichen Projekt üblich).
2. Bestehende FTL-Dateien als Referenz für Custom-Page-`pageId`s und benötigte
   `stepData`-Attribute durchgehen (1:1-Mapping FTL-Dateiname → Keycloakify-Page-Komponente).
3. Generischen Fallback (`orchestrator-tool.ftl`-Äquivalent) zuerst bauen, dann gezielt
   Tool-für-Tool-Overrides ergänzen — inkrementelle Migration.
4. Gradle-Staging (`npmInstall`/`npmBuild`/`stageKeycloakArtifact`) analog zum bestehenden
   `frontend/`-Wiring ergänzen.
5. End-to-End gegen echtes Keycloak (Podman-Compose) testen.
