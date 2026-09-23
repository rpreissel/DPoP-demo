> Eine Journey aus dem Katalog. Die gemeinsame Lesehilfe zu den Diagrammen steht in
> [../04-orchestrierung.md](../04-orchestrierung.md), Abschnitt 3.

# `FAST_ACCESS`

Erst die Fallback-Kette vom bequemsten zum aufwendigsten Weg, danach die Pflichtzustände für den
nächsten Login.

```mermaid
stateDiagram-v2
  [*] --> Start
  Start --> PreferredAuth: verknüpftes Gerät mit Device-Methode
  Start --> AuthChoice: Account bekannt, andere Methoden vorhanden
  Start --> REGISTER: nichts Vorhandenes greift

  PreferredAuth --> AuthChoice: abgelehnt
  AuthChoice --> AuthChoice: ein Tool abgelehnt, weitere übrig
  AuthChoice --> REGISTER: alle abgelehnt

  PreferredAuth --> Finished: Nachweis reicht für das geforderte Niveau
  AuthChoice --> Finished: Nachweis reicht für das geforderte Niveau
  PreferredAuth --> Enrolling: Konto erreicht das Niveau nicht
  AuthChoice --> Enrolling: Konto erreicht das Niveau nicht

  Enrolling --> Enrolling: Methode eingerichtet, Niveau reicht noch nicht
  Enrolling --> Finished: Niveau erreicht

  Enrolling --> RE_IDENTIFY: keine Einrichtung schließt die Lücke, Re-Identifizierung möglich
  RE_IDENTIFY --> Start: Identität bestätigt (SubJourneyFinished)
  RE_IDENTIFY --> [*]: abgelehnt/nicht möglich (Cancel/Abort)

  REGISTER --> Start: REGISTER-Journey fertig (SubJourneyFinished)
  REGISTER --> [*]: abgelehnt/nicht möglich (Cancel/Abort)

  Finished --> [*]

  note right of AuthChoice
    Zustände 1-2: Fallback.
    Ablehnen führt weiter.
  end note
  note right of Enrolling
    Pflichtzustand: nur Erfüllen
    führt weiter. Geteilter
    Werttyp mit REGISTER
    (siehe Abschnitt "REGISTER").
  end note
  note right of RE_IDENTIFY
    Eigene geteilte SubJourney,
    kein Zustand dieses Intents -
    siehe Abschnitt "RE_IDENTIFY".
  end note
  note right of REGISTER
    REGISTERs eigene Journey als
    Voraussetzung, gleiches Muster
    wie RE_IDENTIFY - siehe unten.
  end note
```

`PreferredAuth` trägt genau die eine vorgeschlagene `toolId`; `AuthChoice`/`Enrolling` tragen
Angebot und Ablehnungen (Fallback- bzw. Pflichtsemantik, s. o.) und sind geteilte Werttypen mit
`RegisterState` (Abschnitt „REGISTER" unten). `Enrolling` trägt zusätzlich `emailObligation`, das
`FAST_ACCESS` selbst nie setzt (immer `false`) — nur ein Lauf über `RegisterState.Identifying`
kennt die E-Mail-Pflicht (Abschnitt 8).

`FAST_ACCESS` identifiziert nie selbst: Fehlt ein Account oder ist jede Methode abgelehnt, läuft
`REGISTER`s eigene Journey als `Transition.RequireSubJourney`-Voraussetzung; schließt in
`Enrolling` keine Einrichtung die Lücke, fragt die geteilte `RE_IDENTIFY`-SubJourney (Abschnitt
„RE_IDENTIFY" unten). Nach `SubJourneyFinished` prüft `Start` per `afterProof` erneut, ob der
Nachweis reicht.

In einem Fallback-Zustand sammelt `declined` die verworfenen Tools, in einem Pflichtzustand
**nicht** (s. o.).
