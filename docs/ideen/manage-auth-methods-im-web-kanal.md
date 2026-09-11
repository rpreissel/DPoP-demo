# Idee: Anmeldeverfahren im Web-Kanal verwalten (`MANAGE_AUTH_METHODS` per Required Action)

Status: **Konzept, nicht umgesetzt**. Konkreter Auslöser: `enroll-qr`
([QR-Login über die App](qr-login-ueber-app.md)) soll auch aus dem Web-Kanal-Demo heraus
aufrufbar sein — bisher existiert es nur als APP-Kanal-Tool
(`frontend/src/tools/qr/EnrollQrForm.tsx`).

---

## 1) Ausgangslage

`AuthIntent.MANAGE_AUTH_METHODS` ist orchestrator-seitig vollständig implementiert und
kanalneutral (`docs/04-orchestrierung.md` Abschnitt "MANAGE_AUTH_METHODS", `docs/05-api.md`
Abschnitt "Methoden verwalten") — aber ausschließlich über den APP-Kanal erreichbar:
`POST /orchestrator/api/v1/channels/{channelSessionId}/enrollments` (`ChannelController.kt`)
setzt eine bereits `AUTHENTICATED`-Session voraus und ist bisher nur aus
`frontend/src/tools/qr/EnrollQrForm.tsx` & Co. heraus aufgerufen worden. Wichtig:
`MANAGE_AUTH_METHODS` ist **kein** `AuthIntent.isEntryIntent`-Wert
(`src/main/kotlin/com/example/dpop/orchestrator/journey/AuthIntent.kt`) — es wird nie über den
Kanal-Einstiegspunkt (`POST /channels`, bzw. web-seitig `PATCH /kc/channels/{id}`) gestartet,
sondern immer über einen eigenen Endpunkt auf einem bereits laufenden, `AUTHENTICATED`-Kanal.
Entsprechend akzeptiert der Web-Kanal-Einstiegspunkt (`KcChannelService.entryIntentFor`) heute
nur `kc_select_method` (unverändert) und `register` — ein `intent=manage_auth_methods` an dieser
Stelle wäre der falsche Ansatzpunkt und würde mit `409` abgelehnt.

Zwei Vorgaben für eine Web-Kanal-Lösung:

1. **Kein erzwungener zweiter Login.** Der Nutzer ist im Web-Kanal bereits über eine gültige
   Keycloak-SSO-Session authentifiziert; die Methodenverwaltung soll sich nicht wie ein separater
   Login-Vorgang anfühlen.
2. **Generisch für alle Verfahren**, nicht auf QR beschränkt — der neue Web-Einstiegspunkt soll
   dieselbe Kandidatenliste anbieten, die der App-Kanal über `enrollments` bereits bekommt
   (Passwort, Gerät, E-Mail, QR, …), nicht ein QR-spezifischer Sonderweg.

---

## 2) Kernbefund: Der Einstiegspunkt ist bereits facade-neutral

`POST .../channels/{channelSessionId}/enrollments` prüft die Anruferberechtigung über
`@BindingKey bindingKeyRef: String`, aufgelöst durch
`src/main/kotlin/com/example/dpop/orchestrator/dpop/DpopBindingKeyResolver.kt` — denselben
Resolver, den die bereits vom Keycloak-Plugin genutzten Tool-Endpunkte (`activateTool`/
`patchTool`, aus `OrchestratorAuthenticator.action()`) längst verwenden. Die Klassendokumentation
dort ist eindeutig:

> "the generic tool endpoints... are facade-neutral by design - the kc-facade's
> OrchestratorAuthenticator calls them exactly like the App client does, just with a signed
> Keycloak assertion instead of a DPoP proof."

Der Resolver faltet eine gültige Peer-Auth-Assertion automatisch in ein `"kc:"`-präfigiertes
Binding-Key-Format (`DeviceChannelAccessGuard.KC_ANCHOR_PREFIX`), das
`DeviceChannelAccessGuard.requireChannel` bereits kennt und gegen `channel.channelAnchor` prüft.
**`.../enrollments` ist also schon heute für Keycloaks Peer-Auth erreichbar** — es fehlt nur ein
Java-Client-Wrapper auf Keycloak-Seite (`OrchestratorClient.startEnrollments(channelSessionId)`,
eine Zeile, analog zu `activateTool`). Keine neue Orchestrator-API nötig.

## 3) Kernbefund: "Kein erzwungener zweiter Login" ist bereits gelöst — durch `OrchestratorResumeAuthenticator`

`OrchestratorResumeAuthenticator` läuft bereits heute als erster Schritt in
`orchestrator-browser` (V2) und `orchestrator-registration` (V7)
(`keycloak-extension/src/main/java/com/example/dpop/kcext/OrchestratorResumeAuthenticator.java`).
Bei bestehender gültiger SSO-Session leitet er die für diesen Flow-Durchlauf zwangsläufig neue
`channelSessionId` her (`OrchestratorNotes.channelSessionId` erzeugt pro Flow-Durchlauf bewusst
einen neuen Wert, um Anker-Kollisionen zwischen z. B. Login und einem direkt folgenden Step-up zu
vermeiden — siehe die eigene Dokumentation der Methode), holt aber `restoreData` — Evidenz, die
der *vorige* Flow-Durchlauf am Ende auf Keycloaks eigenem `UserSessionModel` hinterlassen hat
(`OrchestratorNotes.stashRestoreDataAtFlowEnd`) — und reicht sie beim ersten `upsertChannel`-Aufruf
des neuen Kanals durch. Das ist bereits die Brücke von "Keycloaks SSO-Session weiß, dass ich schon
angemeldet bin" zu "dieser neue Orchestrator-Kanal ist ebenfalls `AUTHENTICATED`" — ohne erneuten
Nachweis, ohne dass dafür etwas Neues gebaut werden müsste.

## 4) Vorgeschlagenes Design

1. **Kein neuer Client, kein neuer Authentication-Flow.** `orchestrator-browser` (V2) bleibt
   unverändert. Einstieg über den bestehenden `dpop-demo-web`-Client, dieselbe `/auth`-URL wie
   der bestehende `login()`, ergänzt um `kc_action=orchestrator-manage-methods` — Keycloaks
   eingebauter Mechanismus für "bereits angemeldeter Nutzer löst selbst eine Zusatzaktion aus"
   (vgl. Keycloaks eigene "Passwort ändern"/"OTP einrichten"-Selbstbedienungslinks). Passt zu
   der in [web-keycloak-kanal.md](web-keycloak-kanal.md) §13 selbst schon benannten Einordnung:
   *"eine Keycloak-'Required Action'... für Touchpoints außerhalb eines zusammenhängenden
   Login-/Step-up-Flows... für die keine über mehrere Schritte lebende Journey-Ressource sinnvoll
   ist."*
2. **Neuer, generischer Provider `OrchestratorManageMethodsRequiredAction`**
   (`RequiredActionProvider`/`RequiredActionFactory`, `getId()="orchestrator-manage-methods"`,
   `defaultAction=false` — nur über `kc_action` erreichbar, nie erzwungen). Ruft beim Eintritt
   `client.startEnrollments(channelSessionId)` und rendert das zurückkommende `next` über den
   unveränderten `WebToolRenderer`/`WebToolRendererFactory`-Dispatch
   (`keycloak-extension/.../webtool/`) — gleicher `challenge()`/`processAction()`-Loop wie
   `OrchestratorAuthenticator.action()`, sinnvollerweise als gemeinsamer Helper extrahiert statt
   dupliziert (beide Context-Typen exposen `.form()` → `LoginFormsProvider`).
3. Realm-Registrierung der Required Action über eine kleine Migration
   (`RequiredActionProviderRepresentation`, `enabled=true`, `defaultAction=false`).
4. **Frontend**: `frontend/src/webOidc.ts` — neue `redirectToManageMethods()`, baut dieselbe
   `/auth`-URL wie `redirectToLogin`, ergänzt nur `kc_action`. Rückkehr über den bereits
   bestehenden `completeLoginIfRedirected()`-Pfad. `WebChannelView.tsx`: neuer Button
   "Anmeldeverfahren verwalten".

### Ablauf im Detail

1. Browser hat aus `login()` bereits ein gültiges Keycloak-SSO-Cookie.
2. Klick "Verwalten" → Redirect zu `/auth`, gleicher Client, `kc_action=orchestrator-manage-methods`.
3. `orchestrator-browser`-Flow läuft wie gewohnt durch: Cookie-Reuse (kein Formular) →
   `OrchestratorResumeAuthenticator` bringt den frischen Kanal per `restoreData` auf
   `AUTHENTICATED`, sofern die Evidenz noch reicht (sonst greift die normale Login-/Step-up-
   Kaskade — kein neues Verhalten).
4. Flow schließt normal ab (`context.success()`) → Keycloak führt die anstehende Required Action
   aus: `startEnrollments(channelSessionId)` auf dem bereits `AUTHENTICATED`-Kanal.
5. `orchestrator-select.ftl` zeigt die Kandidatenliste (Passwort, Gerät, E-Mail, QR, …).
6. Auswahl → Tool-spezifische Seite → Abschluss → Journey fertig → Required Action meldet Erfolg
   → Keycloak-Redirect zurück zu `redirect_uri`, wie bei jedem Login.
7. Browser landet wieder im selben Tab, über den bereits bestehenden
   `completeLoginIfRedirected()`-Codepfad.

## 5) Zusätzlich nötig für den konkreten Auslöser (`enroll-qr`)

`WebToolAvailability.renderableToolIds()` deklariert dem Orchestrator, welche `toolId`s Keycloak
rendern kann — gebildet ausschließlich aus registrierten `WebToolRendererFactory`-Providern
(`keycloak-extension/src/main/resources/META-INF/services/com.example.dpop.kcext.webtool.
WebToolRendererFactory`). Dort stehen `AuthQrRendererFactory`/`AuthQrLookupRendererFactory`
(Login-Seite von QR), aber **kein Eintrag für `enroll-qr`** — ohne eigenen Renderer würde
`enroll-qr` also gar nicht erst als Kandidat angeboten, unabhängig vom Punkt oben.

Ausdrücklich **keine generische `orchestrator-tool.ftl`-Fallback-UI** — stattdessen eine eigene
Seite, die `frontend/src/tools/qr/EnrollQrForm.tsx` spiegelt:

- Neu: `keycloak-extension/.../webtool/qr/EnrollQrRendererFactory.java`
  (`AbstractWebToolRendererFactory`, Bauweise wie `PasswordEnrollRendererFactory`):
  `getId()="enroll-qr"`, `title()`/`hint()` für die Kandidatenliste, `render()` prüft
  `"enroll".equals(ctx.step())` (Schrittname bestätigt über
  `src/test/kotlin/.../tool/ToolCatalogStartStepTest.kt`: `"enroll-qr" to "enroll"`), liefert
  `form.createForm("tool-qr-enroll.ftl")`.
- Neu: `keycloak-extension/.../theme/orchestrator/login/tool-qr-enroll.ftl`, Aufbau wie
  `tool-qr-wait.ftl` (gleiches `template.ftl`-Layout): Überschrift "Web-Login per QR erlauben",
  Erklärtext, ein Formular mit genau einem Submit-Button ("Aktivieren"),
  `action="${url.loginAction}"`, `method="post"`, keine Felder — der Klick selbst ist die
  Bestätigung, wie `confirmEnrollQr` im App-Kanal (leeres PATCH).
- SPI-Registrierung: neue Zeile in der `WebToolRendererFactory`-Services-Datei.

## 6) Zu prüfen vor Umsetzung

- Exakte Required-Action-Registrierung über die Migrations-Tooling-API, analog zum in `V1`–`V8`
  genutzten Muster.
- Ob `OrchestratorNotes.channelSessionId`/verwandte Helfer unverändert auch über
  `RequiredActionContext` funktionieren oder eine kleine Signatur-Erweiterung brauchen (aktuell an
  `AuthenticationFlowContext` orientiert).
- Randfall: Required Action läuft nur, wenn der vorangehende Flow tatsächlich mit einem
  authentifizierten `context.getUser()` abschließt — an echtem Keycloak verifizieren.
- Gemeinsamer Helper für den Render-Dispatch zwischen `OrchestratorAuthenticator` und der neuen
  Required Action, um Duplikation zu vermeiden.

## 7) Verifikation

- Bestehende orchestrator-seitige Tests als Referenz: `ManageMethodsIntegrationTest.kt`,
  `AuthQrFlowIntegrationTest.kt`.
- End-to-End gegen den echten Podman-Stack: Web-Kanal-Login → "Anmeldeverfahren verwalten" (kein
  sichtbares zweites Login-Formular bei frischer Session) → `enroll-qr`-Seite zeigt die neue
  explizite UI → Aktivieren → `GET .../methods` zeigt `qr` als aktive Methode.
