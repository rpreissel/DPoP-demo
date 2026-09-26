# Idee: Fachkern und Technik im Orchestrator und im Konto-Modul trennen

> **Status: offen, nicht entschieden.** Ein Vorschlag zur Diskussion (2026-09-26), keine Freigabe zur
> Umsetzung. Ziel: Wer die Fachlichkeit lesen will (Was passiert bei einer Registrierung? Wann darf
> ein Gerät umgebunden werden? Wann ersetzt ein Anker den anderen?), soll sie finden, ohne durch
> Transaktionen, Repositories und Serialisierung zu lesen. Betrifft die Module `orchestrator` und
> `account` ([08-projektrahmen.md](../08-projektrahmen.md) Abschnitt 3).

## 1. Was heute schon gut getrennt ist

Die Vorschläge unten rütteln nicht daran:

- **Strategien entscheiden nur.** `IntentStrategy.transition(state, event, ctx)` liefert eine
  `Transition`; alles mit Wirkung läuft über `Action` und `JourneyActionExecutor`.
  `OrchestratorArchitectureTest` erzwingt, dass eine Strategie keinen `@Service` und kein
  Repository kennt ([04-orchestrierung.md](../04-orchestrierung.md) Abschnitt 5). `RegisterStrategy`
  liest sich wie das Zustandsdiagramm.
- **`AuthPolicy` ist eine reine Regelklasse** (`orchestrator/policy`): kein Repository, keine
  Transaktion, nur der Tool-Katalog.
- **`RunningJourney` und `LiveChannel`** geben ungültige Zustände gar nicht erst in die Hand
  (privater Konstruktor, `of(...)` weist Beendetes ab).

Das Problem liegt eine Schicht tiefer: Sobald eine Entscheidung *ausgeführt* wird, mischen sich Regel
und Technik.

## 2. Befund

- **Paketnamen sagen nichts über fachlich oder technisch.** `orchestrator.session` enthält 7
  JPA-Entities, 7 Repositories, 14 Services und 5 reine Typen nebeneinander. `orchestrator.journey`
  mischt die Zustandsmaschine (`IntentStrategy`, `state/`, `strategy/`) mit `AuthJourneyRepository`,
  `JourneyStateCodec` und `AuthJourneyLogging`. In `account.internal` liegen Entities und
  Repositories neben `ClaimLedger`, `AnchorRegistry`, `IdentityMatchingService` und `PassportForm`.
- **Die Journey-Zustände hängen an Jackson.** `journey/state` trägt 25 `@JsonTypeInfo`- und
  `@JsonSubTypes`-Annotationen; der Fachleser sieht Serialisierung, wo er Zustände sehen will.
- **Die wichtigsten Sicherheitsregeln stehen zwischen Speicherzugriffen.** `JourneyActionExecutor`
  (758 Zeilen) und `JourneyService` (686 Zeilen) enthalten „nie implizit auf ein anderes Konto
  umbinden“ (`linkDeviceIfIntentImplies`), „bei mehreren Instanzen zählt die niedrigste Stufe“
  (`performAcceptProof`), „ein identifiziertes Konto nimmt keine zweite Identität an“
  (`performRecordIdentification`/`accountOf`), verwoben mit `journeyRepository.save` (15 Aufrufe),
  `channel.accountId = to` und `findLinkedAccountId`.
- **Die Fachlogik arbeitet auf JPA-Entities.** `ChannelSession` und `AuthJourney` haben durchweg
  nullbare `var`-Felder. Die Folge sind 88 `checkNotNull`/`!!` in `journey`, `channel` und
  `session`; das ist Technik, die als Rauschen in jeder fachlichen Methode steht.
- **Im Konto-Modul liegt die Entscheidung in der Persistenz.** `AnchorRegistry.bind` enthält die
  zentrale Ankerregel (fremd gehalten → Konflikt; eigener Wert → nur ersetzen, wenn `AnchorRule` es
  erlaubt; Preis nach `acrFloor`), verwoben mit `findByAttributeTypeAndValue` und `save`.
  `ClaimLedger.append` hat denselben Aufbau mit dem Schlüssel gegen Doppeleinträge.
- **Kein Lesepfad im Code.** [04-orchestrierung.md](../04-orchestrierung.md) hat einen guten
  „Einstieg für Fachexperten“; im Code fehlt das Gegenstück.

## 3. Vorschläge, nach Nutzen je Aufwand

### 3.1 Pakete nach „fachlich / anwendend / technisch“, und das per Test erzwingen

- Je Modul: `domain` (Zustände, Strategien, Policy, Regeln, Wertobjekte – alles ohne Framework),
  `application` (Services, Transaktionen, Executor), `infrastructure` (Entities, Repositories,
  Codec, Keycloak-Client, Logging), dazu das bestehende `api`.
- Eine ArchUnit-Regel: `..domain..` importiert nichts aus `jakarta`, `org.springframework`,
  `org.hibernate`, `jackson`, `slf4j`. Damit ist „fachlich“ keine Konvention, sondern geprüft. Die
  bestehende DAG-Regel der Pakete bleibt.
- `orchestrator.kernel` (Intents, ACR-Stufen, Kanaltypen, Fehlercodes) ist heute schon fast dieses
  `domain` und würde der Anker.
- Aufwand: fast nur Verschieben. Die Vorschläge 3.2 bis 3.5 machen `domain` dann Schritt für Schritt
  voller.

### 3.2 Die Regeln aus dem Executor herauslösen; der Executor bleibt Ablauf

- Jede Regel wird eine benannte reine Funktion oder ein kleines Objekt in `domain` mit einem
  `sealed` Ergebnis, zum Beispiel:
  - `EffectiveAcr.forProof(activeInstances, keyBinding, bindingKeyRef, achieved)`
  - `DeviceRebind.decide(linkedTo, accountId, intent)` → `Link`, `Keep`, `NeedsConsent`
  - `IdentityAdoption.decide(inHand, attested, matches)` → `Adopt`, `Merge`, `Reject(reason)`
- `JourneyActionExecutor` macht nur noch „lesen → Regel fragen → schreiben“. Die Regeln bekommen
  Tabellentests ohne Spring; der Fachleser findet sie an einem Ort statt zwischen zwei
  `save`-Aufrufen. Die langen KDoc-Begründungen wandern mit an die Regel.
- Größter Gewinn; Regel für Regel machbar.

### 3.3 Im Konto-Modul Entscheidung und Persistenz trennen

- `AnchorDecision.decide(existing, heldElsewhereBy, rule, provenAcr)` liefert `Bind`,
  `Replace(retractOldValue)`, `Idempotent` oder `Reject(text)`; `AnchorRegistry` führt nur aus.
  Genauso `ClaimDedup` für den Schlüssel in `ClaimLedger.append`.
- `AccountService` ist heute Fassade *und* Implementierung des Ports `tool_api.AccountDirectory`.
  Ein kleiner `AccountDirectoryAdapter` trennt die Fassade von der Portrolle; ein Leser sieht dann,
  was für das Modul und was für die Tools ist.

### 3.4 Die Journey-Zustände von Jackson befreien

- Die Zuordnung „Diskriminator ↔ Klasse“ gehört in `JourneyStateCodec` (eine Tabelle je Intent,
  über Jackson-Mixins oder `registerSubtypes`). Die Zustände werden reine `sealed`-Hierarchien.
- Ein Test prüft, dass jede Unterklasse einer `JourneyState`-Hierarchie im Codec eingetragen ist;
  das ersetzt, was die Annotation heute erzwingt.

### 3.5 Die Fachlogik nicht mehr auf Entities arbeiten lassen

- `LiveChannel` und `RunningJourney` sind heute nur die Eintrittskarte; die privaten Methoden
  greifen sofort wieder auf die Entity zu. Beide Zeugen bekommen die nicht-nullbaren Lesezugriffe
  und die erlaubten Zustandsübergänge (`bindAccount`, `consume`, `markCancelled`); Executor und
  `JourneyService` nehmen nur noch sie an. Die Entity bleibt in `infrastructure` und wird nur noch
  vom Zeugen und vom Repository angefasst.
- Das ist derselbe Schnitt, den A-6 (zweite Bewertung) für `AccountService` gemacht hat, eine Ebene
  tiefer. Aufwand: mittel, gut in Scheiben teilbar.

### 3.6 Einen Lesepfad in den Code legen

- Je Modul nennt `ModuleMetadata.kt` in seinem KDoc die drei bis vier Dateien, mit denen man
  anfängt (für `orchestrator`: `AuthIntent`, `IntentStrategy`, ein Strategie/Zustands-Paar,
  `AuthPolicy`; für `account`: `AccountProfile`, `AnchorRule`/`ClaimSource` in `tool_api`/`tool_spi`,
  `ClaimLedger`, `AnchorRegistry`).
- Optional, später: Die Zustandsdiagramme in 04 aus den Strategien erzeugen. Weil die Strategien
  rein sind, kann ein Test je Zustand und Ereignis die `Transition` gegen Beispielkontexte
  aufzählen und als Mermaid schreiben. Dann können Diagramm und Code nicht mehr auseinanderlaufen.

## 4. Was bewusst nicht vorgeschlagen wird

- **Keine vollständige „hexagonale“ Architektur** mit Port-Interfaces für jedes Repository. Die
  Modulgrenzen über `tool_api`/`tool_spi` leisten das schon; nach innen wäre es Zeremonie.
- **Kein generisches Domänen-Framework** über den Modulen (Entscheidung zu A-3 in der zweiten
  Bewertung: Duplikation bei den Tools ist gewollt). Die zwei Module sind verschieden genug.
- **Kein Abbau der KDoc-Erklärungen.** Sie sind lang, aber sie tragen die Begründungen; mit 3.2
  wandern viele davon an die Regel, wo sie hingehören.

## 5. Umsetzungsplan

Jeder Schritt ist ein eigener Commit nach grünem vollem Lauf (`./gradlew test -PstrictTexts`,
`:keycloak-extension:test`, E2E). Kein Schritt ändert Verhalten oder API; der OpenAPI-Snapshot
bleibt gleich. Die Aufwände sind Schätzungen für eine Person.

### Schritt 1 – Regel zuerst, dann Struktur (0,5 Tag)

- ArchUnit-Regel `DomainStaysFreeOfFrameworks` in `OrchestratorArchitectureTest` und einem neuen
  `AccountArchitectureTest`: Klassen unter `..orchestrator.domain..` und `..account.domain..`
  importieren nichts aus `jakarta..`, `org.springframework..`, `org.hibernate..`, `tools.jackson..`,
  `com.fasterxml..`, `org.slf4j..`. Die Regel ist zunächst leer erfüllt.
- `orchestrator.kernel` → `orchestrator.domain` (`git mv`, Importe nachziehen). Die DAG-Regel und
  `ModuleMetadata.allowedDependencies` prüfen, dass nichts nach außen bricht.
- Dazu verschieben, weil sie heute schon frei von Framework sind: `orchestrator.policy`
  (`AuthEvidence`, `AuthPolicy`, `DefaultAuthPolicy` – Letztere trägt `@Component`, siehe Schritt 2),
  `journey.state`, `journey.strategy`, `IntentStrategy`, `CandidateTools`, `DeclineTool`,
  `JourneyRouting`, `RunningJourney`, `session.ChannelState`, `session.LiveChannel`.
- Ergebnis: `domain` existiert, ist geprüft, und der Fachleser hat ein Paket, in dem alles fachlich
  ist.

### Schritt 2 – Die drei `@Component` im Fachkern (0,5 Tag)

- `DefaultAuthPolicy`, `RegisterDispatchStrategy` und die Strategie-Beans tragen Spring-Annotationen
  nur, damit `JourneyService` sie findet. Stattdessen eine `@Configuration` in `application`
  (`JourneyConfig`), die sie als Beans anlegt; die Klassen selbst bleiben ohne Import aus Spring.
- Damit hält die Regel aus Schritt 1 für das ganze `domain`, ohne Ausnahme.

### Schritt 3 – Journey-Zustände ohne Jackson (1 Tag)

- `JourneyStateCodec` bekommt je Intent eine Tabelle `stateType → KClass` (aus den `sealed`
  Unterklassen per `sealedSubclasses` abgeleitet, Name = `simpleName`), registriert als Mixins bzw.
  `registerSubtypes`.
- Die 25 Annotationen aus `journey/state` entfernen.
- Test `JourneyStateCodecTest`: jede Unterklasse jeder `JourneyState`-Hierarchie geht durch
  `write`/`read` unverändert; ein umbenannter Zustand fällt hier auf. Achtung: Die
  Diskriminatornamen müssen die heutigen bleiben, sonst lesen gespeicherte Journeys nicht mehr –
  im Demomodus unkritisch (Flyway-Reset), aber der Test soll die Namen festhalten.

### Schritt 4 – Regeln aus dem Executor herauslösen (2–3 Tage, je Regel ein Commit)

Reihenfolge nach Sicherheitsgewicht; jede Regel bekommt ihren Tabellentest ohne Spring, und der
bisherige Integrationstest bleibt als Gegenprobe:

- `EffectiveAcr.forProof(...)` aus `performAcceptProof` – Test: mehrere Instanzen, Instanz auf
  fremdem Schlüssel, `enrolledUnderAcr` fehlt.
- `DeviceRebind.decide(...)` aus `linkDeviceIfIntentImplies`/`performLinkDevice`/`linkDeviceTo` –
  Test: Gerät frei, an dasselbe Konto, an ein anderes Konto mit und ohne Zustimmung, KEYCLOAK-Kanal.
- `IdentityAdoption.decide(...)` aus `performRecordIdentification`/`accountOf`/`accountOfAttestation`
  – Test: nichts in der Hand, dieselbe Person, andere Person, vorläufiges Konto (ADR-20).
- `MethodRemoval.decide(...)` aus `removeMethod`/`dependentsOf`/`dependentsOfLostClaims` – Test:
  letzte Methode, abhängige Methoden, verlorene Claims.
- `AttemptBudget` aus `chargeAttempt`/`fallBack` in `JourneyService`.
- Nach jedem Schritt: Der Executor ruft die Regel, schreibt das Ergebnis; die KDoc-Begründung
  wandert an die Regel. Die ArchUnit-Regeln „nur die handelnde Phase fragt `IdentityResolver`“ und
  „nur sie schreibt `DeviceAccountLink`“ bleiben, weil das Schreiben im Executor bleibt.

### Schritt 5 – Konto-Modul: Entscheidung von Persistenz trennen (1 Tag)

- `AnchorDecision.decide(existing, heldElsewhereBy, rule, provenAcr)` in `account.domain`;
  `AnchorRegistry.bind` wird „lesen → entscheiden → schreiben“. Tabellentest mit den Fällen aus dem
  heutigen KDoc (fremd gehalten, unveränderlich, Ersetzen unter dem Preis, gleicher Wert in anderer
  Schreibweise).
- `ClaimDedup` für den Schlüssel in `ClaimLedger.append`.
- `AccountDirectoryAdapter` in `account` implementiert `tool_api.AccountDirectory` und delegiert an
  `AccountService`; `AccountService` implementiert den Port nicht mehr selbst. Modulith-Test prüft,
  dass die Tool-Module weiterhin nur den Port sehen.
- `account.internal` aufteilen: Entities und Repositories nach `infrastructure`, `ClaimLedger`,
  `AnchorRegistry`, `IdentityMatchingService`, `PassportForm`, `PersonLookupKey` (ohne die
  `@Value`-Konfiguration, die in eine `@Configuration` wandert) nach `domain` bzw. `application`.

### Schritt 6 – Zeugen statt Entities (2–3 Tage, in Scheiben)

- `LiveChannel` bekommt nicht-nullbare Lesezugriffe (`channelSessionId`, `channelType`, `state`,
  `accountId?`, `bindingKeyRef?` nur auf APP) und die erlaubten Übergänge (`bindAccount`,
  `endWith(state)`); `RunningJourney` entsprechend (`intent`, `state`, `consume`, `cancel`,
  `chargeAttempt`).
- Scheibe 1: `JourneyService` – die privaten Methoden nehmen `RunningJourney`/`LiveChannel` statt
  der Entities. Scheibe 2: `JourneyActionExecutor`. Scheibe 3: `ChannelService`, `KcChannelService`,
  `ToolControllerSupport`.
- Messgröße: die 88 `checkNotNull`/`!!` in `journey`, `channel`, `session` sollen am Ende unter 20
  liegen; die verbleibenden stehen in `infrastructure`.
- Danach `orchestrator.session` aufteilen: Entities und Repositories nach `infrastructure`,
  Throttles, `TokenService`, `SessionManagementService` nach `application`.

### Schritt 7 – Lesepfad und Doku (0,5 Tag)

- `ModuleMetadata.kt` beider Module nennt die Einstiegsdateien.
- [08-projektrahmen.md](../08-projektrahmen.md) Abschnitt 3 beschreibt die drei Paketebenen und die
  ArchUnit-Regel; [04-orchestrierung.md](../04-orchestrierung.md) Abschnitt 5 verweist auf die
  Regelklassen aus Schritt 4.
- Diese Idee wandert nach `docs/archiv/`, ein ADR hält die Entscheidung fest.

### Was den Plan gefährdet

- **Inkrementeller Kotlin-Build bei vielen Verschiebungen:** nach jedem `git mv` sauber bauen
  (bekannte IC-Fehler bei Umbenennungen).
- **Schritt 6 ist der einzige mit echtem Risiko**, weil er die Aufrufreihenfolge im Executor
  berührt. Deshalb zuletzt, in Scheiben, und mit `ModelBasedJourneyTest` als Gegenprobe nach jeder
  Scheibe.
- **Nicht nebenbei aufräumen.** Umbenennungen, Kommentare oder Regeländerungen gehören in eigene
  Commits, sonst ist der Diff der Verschiebungen nicht mehr prüfbar.

## 6. Reihenfolge in Kürze

1. Schritt 1 und 2 – Struktur und Regel.
2. Schritt 4 und 5 – Regeln herauslösen; das füllt `domain`.
3. Schritt 3 – Zustände ohne Jackson.
4. Schritt 6 – Zeugen statt Entities.
5. Schritt 7 kann jederzeit dazwischen.
