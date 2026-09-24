> Eine Journey aus dem Katalog. Wie die Diagramme zu lesen sind, erklärt
> [../04-orchestrierung.md](../04-orchestrierung.md), Abschnitt 3.

# `FAST_ACCESS`

Die schnelle Anmeldung versucht zuerst die bequemen Wege und weicht Schritt für Schritt auf
aufwendigere aus. Danach folgen die Pflichtzustände, die für die nächste Anmeldung sorgen.

```mermaid
stateDiagram-v2
  [*] --> Start
  Start --> PreferredAuth: verknüpftes Gerät mit Geräteschlüssel
  Start --> AuthChoice: Konto bekannt, andere Verfahren vorhanden
  Start --> REGISTER: nichts Vorhandenes passt

  PreferredAuth --> AuthChoice: abgelehnt
  AuthChoice --> AuthChoice: ein Tool abgelehnt, weitere übrig
  AuthChoice --> REGISTER: alle abgelehnt

  PreferredAuth --> Finished: Nachweis reicht für das geforderte Niveau
  AuthChoice --> Finished: Nachweis reicht für das geforderte Niveau
  PreferredAuth --> Enrolling: Konto erreicht das Niveau nicht
  AuthChoice --> Enrolling: Konto erreicht das Niveau nicht
  PreferredAuth --> RE_IDENTIFY: Sitzung unter loa2 - Einrichten erst nach erneuter Identifizierung
  AuthChoice --> RE_IDENTIFY: Sitzung unter loa2 - Einrichten erst nach erneuter Identifizierung

  Enrolling --> Enrolling: Verfahren eingerichtet, Niveau reicht noch nicht
  Enrolling --> Finished: Niveau erreicht

  Enrolling --> RE_IDENTIFY: kein Einrichten schließt die Lücke, erneute Identifizierung möglich
  RE_IDENTIFY --> Start: Identität bestätigt (SubJourneyFinished)
  RE_IDENTIFY --> [*]: abgelehnt oder nicht möglich (Cancel/Abort)

  REGISTER --> Start: REGISTER-Journey fertig (SubJourneyFinished)
  REGISTER --> [*]: abgelehnt oder nicht möglich (Cancel/Abort)

  Finished --> [*]

  note right of AuthChoice
    PreferredAuth und AuthChoice
    sind Ausweichzustände:
    Ablehnen führt weiter.
  end note
  note right of Enrolling
    Pflichtzustand: Nur Erfüllen
    führt weiter. Wird mit REGISTER
    gemeinsam genutzt.
  end note
  note right of RE_IDENTIFY
    Eigene, gemeinsam genutzte
    Sub-Journey, kein Zustand
    dieses Intents.
  end note
  note right of REGISTER
    Die Journey von REGISTER als
    vorgeschalteter Schritt, nach
    demselben Muster wie RE_IDENTIFY.
  end note
```

`PreferredAuth` enthält genau das eine vorgeschlagene Tool (`toolId`). `AuthChoice` und
`Enrolling` enthalten das Angebot und die bisherigen Ablehnungen. Sie werden mit `RegisterState`
gemeinsam genutzt (siehe [`REGISTER`](register.md)). `Enrolling` hat zusätzlich das Feld
`emailObligation`. `FAST_ACCESS` setzt es nie (es bleibt `false`); die E-Mail-Pflicht gibt es nur,
wenn die Journey über `RegisterState.Identifying` gelaufen ist (Orchestrierung, Abschnitt 8).

`FAST_ACCESS` identifiziert nie selbst:

- Gibt es kein Konto, oder hat der Nutzer jedes Verfahren abgelehnt, startet die Journey von
  [`REGISTER`](register.md) als vorgeschalteter Schritt (`Transition.RequireSubJourney`).
- Kann in `Enrolling` kein Einrichten die Lücke schließen, fragt die gemeinsam genutzte Sub-Journey
  [`RE_IDENTIFY`](re-identify.md) nach einer erneuten Identifizierung.

Ist die Sub-Journey fertig (`SubJourneyFinished`), prüft `Start` mit `afterProof` erneut, ob der
Nachweis reicht.

Der Unterschied zwischen beiden Zustandsarten zeigt sich in `declined`: In einem Ausweichzustand
sammelt das Feld die abgelehnten Tools, in einem Pflichtzustand **nicht**, denn dort führt Ablehnen
nicht weiter.
