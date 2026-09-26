# ADR-40: Der fachliche Kern liegt im Paket `domain`, ohne Framework, per ArchUnit geprüft

**Status:** umgesetzt 2026-09-26. Herleitung und Plan:
[Fachkern und Technik trennen](../archiv/fachkern-und-technik-trennen.md).

**Entscheidung.** In `orchestrator` und `account` liegt alles, was ein Entwickler lesen muss, um die
Regeln zu verstehen, in einem Paket `domain`. Dieses Paket benutzt kein Framework (kein Spring,
JPA, Jackson, Logging) und hängt von nichts anderem im eigenen Modul ab. Beides prüft ArchUnit
(`OrchestratorArchitectureTest`, `AccountArchitectureTest`), eine Konvention allein reicht nicht.

## Was wo liegt

- **`orchestrator.domain`** (früher `kernel`, [ADR-27](ADR-027-gemeinsame-typen-im-kernel-paket.md)):
  - das Vokabular: `AuthIntent`, ACR-Stufen, Kanaltyp, Fehlercodes, `ToolCatalog`;
  - `domain.journey`: `IntentStrategy` mit `Transition` und `Action`, die Zustände (`state`) und
    Strategien (`strategy`), dazu die Regeln der handelnden Phase: `AccountRules.kt` (welches Konto
    eine Aktion beschreibt, ADR-18/20) und `CredentialRules.kt` (Stufe eines Nachweises, ADR-5;
    implizites Geräte-Binden; abhängige Verfahren);
  - `domain.policy`: `AuthPolicy`, `DefaultAuthPolicy`, `AuthEvidence`.
- **`account.domain`**: `AnchorDecision` (wann ein Anker gebunden, ersetzt oder abgelehnt wird,
  ADR-11/19), `normalizeClaimValue` und `ClaimKey`, `PassportForm`.
- **Außen herum** bleibt die Technik: `JourneyActionExecutor` und `JourneyService` lesen, fragen die
  Regel und schreiben; `account.application` (Protokolle, Register) und `account.infrastructure`
  (Entities, Repositories). `DomainBeans` im Wurzelpaket des Orchestrators legt Strategien und
  Policy als Spring-Beans an und listet an einer Stelle, welche Strategien es gibt.

## Was es kostet und was bewusst unterblieb

- **Serialisierung außerhalb des Kerns.** Die Journey-Zustände tragen keine Jackson-Annotationen;
  `JourneyStateCodec` leitet den Typnamen aus den `sealed`-Hierarchien ab, `JourneyStateCodecTest`
  hält die gespeicherten Namen fest. Die Frage eines wartenden Zustands heißt fachlich `Question`,
  auf der Leitung weiter `Prompt` (Vertrag unverändert).
- **Kein Adapter für `AccountDirectory`.** `AccountService` implementiert den Port weiter selbst: Er
  wird an mehreren Stellen als Port benutzt, ein Adapter hätte nur umgeleitet.
- **Kein Umbau aller Executor-Methoden auf Zeugen** (`LiveChannel`, `RunningJourney`). Stattdessen
  stehen die Annahmen „nach dem Speichern nie null“ als Zugriffe neben den Entities
  (`ChannelSession.id`, `AuthJourney.requireIntent()` …). Die übrigen `checkNotNull` sind fachliche
  Vorbedingungen und bleiben.
- **`orchestrator.session`, `journey`, `channel` bleiben nach Thema geordnet**, nicht nach
  `application`/`infrastructure`: Nachdem der Kern in `domain` liegt, enthalten sie nur noch Technik.
  Das Konto-Modul ist klein genug für die Dreiteilung.
- **Das Versuchsbudget** („minus eins, bei null scheitern“) ist keine eigene Regelklasse wert.
- **Die Zustandsdiagramme bleiben handgeschrieben.** Ein Versuch, sie aus den Strategien zu
  erzeugen, brachte unbrauchbare Bilder: Ob ein Übergang vorkommt, hängt von einem Kontext ab, den
  jede ausgeführte Aktion verändert, und ein Generator kann statt der Bedingungen („Nachweis reicht
  für das geforderte Niveau“) nur Beispielnamen an die Pfeile schreiben. Geprüft werden stattdessen
  die Zustände in beide Richtungen (`JourneyDiagramsTest`): Jeder gezeichnete Knoten existiert im
  Code oder ist im Diagramm ausdrücklich deklariert, und jeder Zustand eines Intents ist gezeichnet.
  Der Test fand beim ersten Lauf zwei Abweichungen (`EnrollFirstStart` fehlte, ein Hilfsknoten statt
  der Sub-Journey `RE_IDENTIFY`).
- **`IntentStrategy.kt` ist in fünf Dateien geteilt** (`IntentStrategy`, `JourneyContext`,
  `JourneyEvent`, `Transition`, `Action`): Wer nachschlägt, was eine `Transition` ist, liest nicht
  die anderen vier mit.
