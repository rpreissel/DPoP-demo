> Eine Journey aus dem Katalog. Wie die Diagramme zu lesen sind, erklärt
> [../04-orchestrierung.md](../04-orchestrierung.md), Abschnitt 3.

# `LOOKUP_LOGIN`

Anmelden ohne gekoppeltes Gerät: Der Nutzer gibt seine E-Mail-Adresse an und weist eines seiner
Verfahren nach. Angeboten werden alle Tools mit der Rolle `MethodRole.LOOKUP_AUTH`. Die
Kandidatenliste von `AuthPolicy.candidateTools` passt hier nicht, denn sie setzt ein bereits
gefundenes Konto voraus.

```mermaid
stateDiagram-v2
  [*] --> Start
  Start --> Credential: Anmeldeverfahren verfügbar, das nicht an ein Gerät gebunden ist
  Start --> [*]: keines verfügbar - Abort
  Credential --> Credential: ein Tool abgelehnt, weitere übrig
  Credential --> [*]: alle abgelehnt - Cancel
  Credential --> AdditionalFactor: Nachweis erbracht, acrFloor noch nicht erreicht
  Credential --> OfferBinding: Nachweis erbracht, acrFloor erreicht, Gerät mit keinem Konto verknüpft
  Credential --> Finished: Nachweis erbracht, acrFloor erreicht, Gerät schon mit diesem Konto verknüpft
  Credential --> ConfirmDeviceRebind: Nachweis erbracht, Gerät war mit einem anderen Konto verknüpft
  AdditionalFactor --> AdditionalFactor: ein Tool abgelehnt, weitere übrig
  AdditionalFactor --> [*]: alle abgelehnt - Cancel
  Credential --> RE_IDENTIFY: Nachweis erbracht, kein kombinierbares Verfahren übrig, erneute Identifizierung möglich
  AdditionalFactor --> OfferBinding: acrFloor erreicht, Gerät mit keinem Konto verknüpft
  AdditionalFactor --> Finished: acrFloor erreicht, Gerät schon mit diesem Konto verknüpft
  AdditionalFactor --> ConfirmDeviceRebind: acrFloor erreicht, Gerät war mit einem anderen Konto verknüpft
  AdditionalFactor --> RE_IDENTIFY: kein kombinierbares Verfahren übrig, erneute Identifizierung möglich
  RE_IDENTIFY --> Start: Identität bestätigt (SubJourneyFinished)
  RE_IDENTIFY --> [*]: abgelehnt oder nicht möglich (Cancel/Abort)
  OfferBinding --> Finished: Nutzer stimmt zu -> Gerät wird wiedererkannt
  OfferBinding --> Finished: Nutzer lehnt ab -> keine Verknüpfung
  ConfirmDeviceRebind --> Finished: Nutzer stimmt zu -> Gerät wird neu verknüpft
  ConfirmDeviceRebind --> Finished: Nutzer lehnt ab -> Anmeldung und alte Verknüpfung bleiben
  Finished --> [*]

  note right of Credential
    Hier gibt es kein Identifying,
    das ein Konto ÜBERNIMMT: Ohne
    bekanntes Konto ist Identifizieren
    hier kein Weg zur Anmeldung.
    RE_IDENTIFY ist etwas anderes:
    Es bestätigt nur das bereits
    gefundene Konto.
  end note
```

**Das Gerät wiedererkennen.** `OfferBinding` stellt eine freiwillige Frage: „Dieses Gerät für
künftige Anmeldungen wiedererkennen?“ Dafür implementiert der Zustand das allgemeine
Markierungs-Interface `AnswerableState` (Orchestrierung, Abschnitt 5). So erkennt der gemeinsame
Mechanismus den Zustand, ohne `LookupLoginState.OfferBinding` zu kennen. Gehört das Gerät bereits
einem anderen Konto als dem gerade angemeldeten, wechselt die Journey nach `ConfirmDeviceRebind`.
Dort läuft dieselbe Ja/Nein-Frage, mit einem Hinweis, dass die alte Verknüpfung verloren geht. Lehnt der
Nutzer ab, entfällt nur das neue Verknüpfen; die Anmeldung selbst bleibt bestehen.
Ist das Gerät schon mit genau diesem Konto verknüpft, gibt es nichts zu fragen: Die Journey endet
direkt angemeldet.

Die dauerhafte Zuordnung von Gerät zu Konto (`DeviceAccountLink`) entsteht in dieser Journey
**nur** mit Zustimmung und nie nebenbei beim Anmelden.

**Wenn das Niveau nicht reicht.** `AdditionalFactor` setzt die Untergrenze des Kanals durch
(`acrFloor`, Orchestrierung, Abschnitt 8). Bleibt danach kein kombinierbares Verfahren übrig,
bietet die Strategie die erneute Identifizierung nicht selbst an. Sie startet stattdessen die
gemeinsam genutzte Sub-Journey [`RE_IDENTIFY`](re-identify.md). Ist diese fertig, prüft `Start`
mit `settleOrRaise` erneut. Diese Journey bietet bewusst **nicht** an, ein weiteres Verfahren
einzurichten. Die erneute Identifizierung ist dagegen erlaubt, weil dabei kein Credential auf einem
ungeprüften Gerät entsteht.

**Schutz vor dem Ausforschen von Adressen:** Eine unbekannte E-Mail-Adresse erhält dieselbe Antwort
wie ein gefundenes Konto, dessen Nachweis fehlschlägt. Es gibt keine eigene Fehlerform dafür, auch
nicht darin, wann Demo-Werte in der Antwort erscheinen ([API](../05-api.md)). Weiter gehärtet ist
das bewusst nicht; die Antwortzeiten werden zum Beispiel nicht angeglichen.
