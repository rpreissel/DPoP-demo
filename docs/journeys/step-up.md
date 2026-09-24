> Eine Journey aus dem Katalog. Wie die Diagramme zu lesen sind, erklärt
> [../04-orchestrierung.md](../04-orchestrierung.md), Abschnitt 3.

# `STEP_UP`

```mermaid
stateDiagram-v2
  [*] --> Start
  Start --> AuthChoice: kombinierbare Verfahren vorhanden
  Start --> RE_IDENTIFY: kein kombinierbares Verfahren, erneute Identifizierung möglich
  Start --> [*]: weder noch - Abort
  AuthChoice --> AuthChoice: ein Tool abgelehnt, weitere übrig
  AuthChoice --> RE_IDENTIFY: kein kombinierbares Verfahren übrig oder alle abgelehnt, erneute Identifizierung möglich
  AuthChoice --> [*]: alle abgelehnt, keine erneute Identifizierung möglich - Cancel
  AuthChoice --> Finished: targetAcr erreicht
  RE_IDENTIFY --> Start: Identität bestätigt (SubJourneyFinished)
  RE_IDENTIFY --> [*]: abgelehnt oder nicht möglich (Cancel/Abort)
  Finished --> [*]

  note right of RE_IDENTIFY
    Eigene, gemeinsam genutzte
    Sub-Journey, kein Zustand
    dieses Intents.
  end note
```

Jeder Zustand enthält `targetAcr` und `startingAcr`. `targetAcr` ist das Ziel dieses einen Laufs,
nicht die dauerhafte Untergrenze des Kanals (Orchestrierung, Abschnitt 8). `AuthChoice` enthält
außerdem das Angebot und die bisherigen Ablehnungen.

`CONFIRM_PEER_LOGIN` fordert seinen Step-up mit `allowReIdentification = false` an. Dann entfällt
der Weg über `RE_IDENTIFY` ganz, und die Journey endet mit `Abort` oder `Cancel`.

Kann kein aktives Verfahren die Lücke schließen, bietet die Strategie die erneute Identifizierung
nicht selbst an. Sie startet stattdessen die gemeinsam genutzte Sub-Journey
[`RE_IDENTIFY`](re-identify.md). Das betrifft etwa ein Konto mit nur einem aktiven Verfahren, das
allein `loa1` erreicht. Ist die Sub-Journey fertig (`SubJourneyFinished`), prüft `Start` mit
`finishOrContinue` erneut, ob der Nachweis jetzt reicht.
