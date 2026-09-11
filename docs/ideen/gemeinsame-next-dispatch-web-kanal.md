# Idee: Gemeinsame `next`-Klassifikation für OrchestratorAuthenticator/OrchestratorManageMethodsRequiredAction

Status: **Konzept, nicht umgesetzt**. Konkreter Auslöser: `OrchestratorManageMethodsRequiredAction`
hat die acr/amr-Übernahme nach einem Step-up schlicht vergessen zu duplizieren (siehe
`OrchestratorNotes.applyAuthData`, Fix vom 2026-09-11: das Access-Token blieb nach einem Step-up
in "Anmeldeverfahren verwalten" fälschlich bei `loa1`). Der zugrunde liegende Zustand - zwei
komplett getrennte, aber strukturell identische Dispatch-Schleifen für `next` - ist die eigentliche
Fehlerquelle und soll an der Wurzel behoben werden.

---

## 1) Ausgangslage

`OrchestratorAuthenticator` (Login/Step-up im normalen Browser-Flow,
`keycloak-extension/src/main/java/com/example/dpop/kcext/OrchestratorAuthenticator.java`) und
`OrchestratorManageMethodsRequiredAction` ("Anmeldeverfahren verwalten", Required Action, selbe
Verzeichnisebene) haben beide ihre eigene, komplett separate `handleResponse`-Methode, die den
`next` einer `OrchestratorClient.ChannelResponse` interpretiert:

- Auswahlbildschirm zeigen (`next.isSelectMethod()`), inkl. Sonderfall "keine Kandidaten"
- Tool-Formular zeigen (`next.isTool()` mit vorhandener `toolSessionId`)
- Tool automatisch aktivieren (`next.isTool()` ohne `toolSessionId`, rekursiv weiterverarbeitet)

Beide setzen dabei dieselben Session-Notes (`OrchestratorNotes.PENDING_KIND`,
`PENDING_TOOL_ID`, `PENDING_TOOL_SESSION_ID`) mit identischer Logik. Die "tool"-Pending-Stage-
Formularauswertung (`orchestrator_abandon` vs. Felder einsammeln und patchen) ist ebenfalls
wortgleich in `OrchestratorAuthenticator.action()` und
`OrchestratorManageMethodsRequiredAction.processAction()` vorhanden.

`WebFormRenderer`s Klassendoku begründet die bisherige Trennung bewusst damit, dass
`RequiredActionContext` kein `setUser()` kennt und `failure()` eine andere Signatur hat als bei
`AuthenticationFlowContext` - eine vollständige Vereinheitlichung würde mehr kosten als sie spart.
Diese Einschätzung bleibt für die **Reaktion** auf einen `next`-Wert richtig (siehe Abschnitt 3).
Falsch war nur, dass dadurch auch die reine **Klassifikation** ("was bedeutet dieser `next`-Wert
strukturell") dupliziert blieb - und genau dort ist der Bug entstanden.

## 2) Ziel

Eine einzige, von Keycloak-Typen unabhängige Stelle, die aus `next` eine kleine sealed Ergebnis-
Hierarchie ableitet - testbar mit reinem JUnit, ohne laufendes Keycloak, genau wie
`keycloak-extension/src/test/java/com/example/dpop/kcext/NextClassificationTest.java` es für
`OrchestratorClient.Next` bereits vormacht (Kommentar dort: *"Pure logic, no Keycloak runtime
needed"*, geschrieben nach einem ganz ähnlichen Bug - `isSelectMethod()` erkannte
`selectIdentificationMethod` zunächst nicht).

## 3) Was bewusst getrennt bleibt

- **Was "terminal" bedeutet** (`next == null || next.isAuthenticated() || ...`) unterscheidet sich
  fachlich: bei Login/Step-up IST `AUTHENTICATED` das Journey-Ergebnis (`context.success()`); bei
  Manage-Methods ist der Kanal schon vorher `AUTHENTICATED` (Vorbedingung) und bleibt es während
  der ganzen Sub-Journey - siehe Kommentar in
  `OrchestratorManageMethodsRequiredAction.handleResponse` ("checking it here fired on the very
  first response... which is exactly why every enroll-qr attempt looked like 'nothing
  available'"). Diese Prüfung bleibt daher in jedem Caller selbst, **bevor** die gemeinsame
  Klassifikation aufgerufen wird.
- Was bei "keine Kandidaten" bzw. einem unerwarteten `next`-Typ passiert (welche Meldung,
  `context.failure()` ohne Argument vs. `context.failure(AuthenticationFlowError.INTERNAL_ERROR)`,
  Rücksprung zur Liste vs. Fehlerseite) bleibt Caller-spezifisch - nur *welcher Fall* vorliegt wird
  gemeinsam entschieden.
- Die statische `toolId`-Vorauswahl (nur `OrchestratorAuthenticator`, Admin-Konfiguration pro
  Authentication Execution) bleibt dort, unverändert, und läuft weiterhin vor dem Aufruf der
  gemeinsamen Klassifikation.

## 4) Design: neue Datei `OrchestratorNextDispatch.java`

`keycloak-extension/src/main/java/com/example/dpop/kcext/OrchestratorNextDispatch.java`,
package-privat, zwei Teile:

**a) `classify(...)` - rein, keine Keycloak-Typen:**

```java
sealed interface Outcome permits Select, Tool, Unhandled {}
record Select(List<String> options) implements Outcome {}
record Tool(OrchestratorClient.Next next, boolean autoActivate) implements Outcome {}
record Unhandled(OrchestratorClient.Next next) implements Outcome {}

/** next darf nicht null und nicht isAuthenticated() sein - das hat der Caller schon ausgeschlossen. */
static Outcome classify(OrchestratorClient.Next next, OrchestratorClient.ChannelResponse response) {
    if (next.isSelectMethod()) {
        return new Select(response.stepDataOptions());
    }
    if (next.isTool()) {
        return new Tool(next, next.toolSessionId() == null);
    }
    return new Unhandled(next);
}
```

Nutzt nur `OrchestratorClient.Next`/`ChannelResponse` (beide bereits reine Records ohne
Keycloak-Import, `OrchestratorClient.java:273-355`).

**b) `dispatchToolAction(...)` - der "tool"-Pending-Stage-Handler, heute wortgleich in
`OrchestratorAuthenticator.action()` (Zeilen ~128-136) und
`OrchestratorManageMethodsRequiredAction.processAction()` (Zeilen ~142-150):**

```java
static OrchestratorClient.ChannelResponse dispatchToolAction(
        OrchestratorClient client, String channelSessionId, String toolId, String toolSessionId,
        MultivaluedMap<String, String> form
) throws IOException, InterruptedException {
    if ("true".equals(form.getFirst("orchestrator_abandon"))) {
        return client.abandonTool(channelSessionId, toolSessionId, toolId);
    }
    Map<String, String> fields = new LinkedHashMap<>();
    form.forEach((key, values) -> {
        if (!key.startsWith("orchestrator_") && !values.isEmpty()) fields.put(key, values.get(0));
    });
    return client.patchTool(channelSessionId, toolSessionId, toolId, fields);
}
```

Diese Methode ist die mechanische Formularauswertung, nicht die eigentliche State-Machine - sie
bleibt in derselben Datei, weil sie am selben "tool"-Pending-Stage hängt, ist aber nicht Teil der
Keycloak-freien Testbarkeit von `classify()`.

## 5) Änderungen an den beiden Callern

**`OrchestratorAuthenticator.handleResponse`**: nach der bestehenden Terminal-Prüfung und der
statischen `toolId`-Vorauswahl (unverändert) wird `OrchestratorNextDispatch.classify(next,
response)` aufgerufen; ein `switch` über die drei `Outcome`-Typen ersetzt die bisherigen `if
next.isSelectMethod()`/`if next.isTool()`-Blöcke 1:1 (gleiche Notes, gleiche Fehlermeldungen,
gleiche Fehlerbehandlung bei `activateTool`-Fehlschlag). `action()`'s "tool"-Branch ruft statt des
Inline-Blocks `OrchestratorNextDispatch.dispatchToolAction(...)`.

**`OrchestratorManageMethodsRequiredAction.handleResponse`**: exakt dasselbe Muster, mit den
Caller-eigenen Reaktionen (`renderList(...)` statt Fehlerseite, wenn `Select.options()` leer ist,
`context.failure()` ohne Argument bei `Unhandled`). `processAction()`'s "tool"-Branch nutzt
ebenfalls `dispatchToolAction(...)`.

Beide Dateien verlieren dadurch je ~15-20 Zeilen duplizierte Verzweigungslogik; das Verhalten
ändert sich nicht (reine Extraktion).

## 6) Test ohne Keycloak

Neue Datei `keycloak-extension/src/test/java/com/example/dpop/kcext/OrchestratorNextDispatchTest.java`,
im Stil von `NextClassificationTest.java` (plain JUnit 5, kein Keycloak-Server, keine Mocks nötig):

- `selectMethodWithOptionsYieldsSelect()`
- `selectMethodWithNoOptionsYieldsEmptySelect()`
- `toolWithoutSessionIdYieldsAutoActivateTool()`
- `toolWithSessionIdYieldsRenderTool()`
- `neitherSelectNorToolYieldsUnhandled()`

Fixtures wie `NextClassificationTest`: `ChannelResponse`/`Next` direkt per Konstruktor bauen, kein
JSON nötig (`stepDataOptions()` liest aus `stepData` - für `Select` also ein `Map<String, JsonNode>`
mit oder ohne `"options"`-Array übergeben, siehe Record-Felder `OrchestratorClient.java:273-282`).

## 7) Zu prüfen vor Umsetzung / Verifikation

- `./gradlew :keycloak-extension:test` - neue Tests grün, ohne Keycloak/Podman.
- `./gradlew :keycloak-extension:compileJava` - beide Caller kompilieren nach der Umstellung.
- End-to-End-Rauchtest in Chrome: im Web-Kanal einmal normal einloggen (Select-Screen,
  Tool-Formular) und einmal "Anmeldeverfahren verwalten" mit Step-up durchspielen
  (Abbrechen-Button, Hinzufügen, Entfernen) - beide Pfade müssen sich exakt wie vorher verhalten,
  da es sich um reine Extraktion handelt.
