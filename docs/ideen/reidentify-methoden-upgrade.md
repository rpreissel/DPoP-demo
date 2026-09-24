# Idee: Verfahren aufwerten nach erneuter oder erstmaliger Identifizierung (RE_IDENTIFY)

Status: **Konzept, nicht umgesetzt**. Das Dokument beschreibt, was es bedeuten würde, neben
„Anmeldeverfahren verwalten“ (App und Web) einen eigenen Knopf „Identifizieren“ anzubieten. Nach
jeder erfolgreichen Sub-Journey `RE_IDENTIFY` würde an zentraler Stelle geprüft, ob bestehende
Anmeldeverfahren mit niedrigerem `enrolledUnderAcr` nach Zustimmung aufgewertet werden dürfen.

---

## 1) Ausgangslage

`authenticationMethods[].enrolledUnderAcr` ist in diesem Projekt bewusst **für immer festgeschrieben**.
Das ist die Begrenzung durch `enrolledUnderAcr` aus ADR-5 (heute tatsächlich an zwei Stellen wirksam,
siehe dort Nachtrag 2; [12-entscheidungen.md](../12-entscheidungen.md),
[06-ablaeufe.md](../06-ablaeufe.md) Abschnitt 1): Ein Verfahren darf bei der Anmeldung nie mehr
Vertrauen erzeugen, als bei seiner Einrichtung vorhanden war. Ohne diese Regel könnte jemand, der eine
schwache Sitzung übernimmt, dort ein eigenes Verfahren einrichten und damit dauerhaft ein höheres
Niveau erreichen, als er je nachgewiesen hat.

Daraus entsteht aber ein echtes Problem für Nutzer. Ein Beispiel: Jemand registriert sich im
Experiment „Erst Anmeldeverfahren einrichten“ (`RegisterEnrollFirstStrategy`) nur mit SMS
(`sms.enrolledUnderAcr = loa1`, `personId == null`) und weist sich später ganz regulär mit
`ident-fsc` oder `ident-eid` aus. Er bleibt trotzdem für immer auf `loa1` begrenzt, obwohl er seine
Identität inzwischen nachweislich stärker bestätigt hat. Anders als im ursprünglichen
Angriffsszenario handelt hier kein Angreifer, sondern der Inhaber des Kontos selbst, der gerade
freiwillig einen stärkeren Nachweis erbracht hat.

## 2) Grundidee

1. **Ein eigener Knopf:** Neben „Anmeldeverfahren verwalten“ gibt es einen neuen Knopf
   „Identifizieren“ (zuerst in der App; das Web ist eine bekannte Lücke, siehe Abschnitt 6). Er
   startet jederzeit die bereits gemeinsam genutzte Sub-Journey `RE_IDENTIFY` (`ReIdentifyStrategy`),
   egal ob `personId` schon gesetzt ist. Die sonst übliche Schwelle `loa2` gibt es dabei **nicht**:
   Die erneute Identifizierung ist ja gerade der Weg, auf dem Vertrauen wächst; eine Schwelle davor
   wäre widersinnig.
2. **Eine zentrale Prüfung für JEDEN Aufrufer:** Nicht nur beim neuen Knopf, sondern bei allen
   heutigen Aufrufern von `RE_IDENTIFY` (`STEP_UP`, `LOOKUP_LOGIN`, `AuthEnrollCore` bei `REGISTER`,
   `RegisterEnrollFirstStrategy`) wird nach einer erfolgreichen Identifizierung geprüft, ob es aktive
   Verfahren mit niedrigerem `enrolledUnderAcr` gibt. Wenn ja, wird der Nutzer gefragt, ob diese
   Verfahren (einzeln aufgezählt) künftig bis zum neu erreichten Niveau zählen dürfen. Ist keines
   betroffen, läuft alles unverändert weiter: kein zusätzlicher Schritt und keine Änderung im
   Normalfall.
3. **Zustimmung führt zur Aufwertung:** Bei „Ja“ wird `enrolledUnderAcr` der genannten Verfahren
   angehoben, begrenzt durch das eigene `maxAcr` des jeweiligen Tools; ein Verfahren darf nie mehr
   können, als es technisch je nachweisen könnte. Das ist eine bewusste Ausnahme von ADR-5, die nur mit
   **Zustimmung** gilt, und keine Aufhebung von ADR-5. Auslöser ist immer ein neuer, erfolgreicher
   Nachweis der Identität durch den Inhaber des Kontos selbst, nie eine automatische oder
   stillschweigende Änderung. Bei „Nein“ bleibt alles, wie es ist.

## 3) Wo das technisch ansetzen würde

`RE_IDENTIFY` ([`ReIdentifyStrategy.kt`](../../src/main/kotlin/com/example/dpop/orchestrator/journey/strategy/ReIdentifyStrategy.kt),
Zustände in `ReIdentifyState.kt`) ist nie der Einstieg einer Journey. Man erreicht sie nur über
`Transition.RequireSubJourney`. Heute führt ein erfolgreiches `Completed(Identified)` im Zustand
`Identifying` direkt zu `Action.RecordIdentification` und danach in jedem Fall zu
`Transition.Authenticated`. Genau dazwischen, also nachdem `Action.RecordIdentification` gelaufen ist
und bevor `Transition.Authenticated` zurückgegeben wird, käme ein neuer, dritter Zustand hinzu:
`OfferMethodUpgrade`, ein `AnswerableState` wie das schon bestehende `OfferReIdent`. Er zählt die
betroffenen Verfahren auf und fragt.

Weil diese Stelle **innerhalb** von `ReIdentifyStrategy` liegt, gälte die Prüfung von selbst für alle
fünf Aufrufer (`STEP_UP`, `LOOKUP_LOGIN`, `AuthEnrollCore`, `RegisterEnrollFirstStrategy` und der
neue Knopf in der Verwaltung), ohne dass einer von ihnen geändert werden müsste. Neu hinzukommen
müsste als sechster Aufrufer nur der Zustand `IdentifyRequested` in
`ManageAuthMethodsStrategy`/`ManageAuthMethodsState`, ohne Schwelle davor.

Bei Zustimmung bräuchte es:

- eine neue `Action` (`Action.UpgradeMethods(accountId, methodInstanceIds, newAcr)`), die der
  `JourneyService` ausführt. Dort wird jedes Verfahren auf das `maxAcr` seines Tools begrenzt, weil
  `AccountService` bewusst nichts von `tool_spi` weiß.
- eine neue Methode zum Speichern, `AccountService.upgradeMethods(...)`, nach dem Muster des
  bestehenden `deactivateAuthenticationMethod`. Sie findet die Verfahren über ihre `id` und nicht
  über den Namen des Verfahrens, damit mehrere aktive Instanzen desselben Verfahrens (etwa mehrere
  Geräte) nicht verwechselt werden.

`ToolOutcome.Completed.Identified.achievedAcr` setzen beide Handler zuverlässig
(`IdentFscToolHandler.kt:77`, `IdentEidToolHandler.kt:72`: `achievedAcr = descriptor.maxAcr`, also
`loa2` bzw. `loa3`); das ist also kein Hindernis. Als „gerade erreichtes Niveau“ sollte die neue
Prüfung trotzdem nicht `state.targetAcr` verwenden. Das ist nur die *Mindestanforderung*, mit der die
Sub-Journey gestartet wurde. Beim neuen Knopf ohne Schwelle wäre sie zum Beispiel bewusst `loa1`,
damit `ident-fsc` als Kandidat nicht wegfällt. Sie kann also niedriger sein als das tatsächlich
erreichte Niveau. Zuverlässiger ist `ctx.currentAcr`
(`JourneyContext.currentAcr = policy.resolveAcr(evidence, account)`), und zwar in dem Moment, in dem
nach dem Ausführen von `Action.RecordIdentification` das `ActionCompleted` in `Identifying` eintrifft.
Zu diesem Zeitpunkt hat `recordToolCompletion` die neue `MethodEvidence` (mit `loa = achievedAcr`)
schon übernommen, und der Kontext für den folgenden Aufruf wird neu aufgebaut. `ctx.currentAcr` zeigt
dann genau das erreichte Niveau.

## 4) Frontend

- **App-Kanal:** `AuthenticationCompletedView.tsx` zeigt „Anmeldeverfahren verwalten“ schon mit
  Knöpfen zum Hinzufügen und Entfernen und mit der Obergrenze `enrolledUnderAcr` je Verfahren an. Ein
  Knopf „Identifizieren“ ließe sich dort ohne Weiteres einreihen. Die allgemeine Bestätigungsseite
  (`Prompt.Confirm`), die die App für jeden `AnswerableState` ohnehin anzeigt, würde auch
  `OfferMethodUpgrade` richtig darstellen, ohne dass sich im Frontend etwas ändern muss (reiner Text
  aus `stepData.prompt`).
- **Web-Kanal:** Dort läuft die Verwaltung der Verfahren inzwischen als Required Action von Keycloak
  mit eigener Journey (ADR-8, Nachtrag). Der neue HTTP-Endpunkt wäre nicht an einen Kanal gebunden.
  Einen Knopf zum Identifizieren gibt es dort aber noch nicht; er müsste in der Seite der Required
  Action ergänzt werden. Für eine erste Umsetzung könnte man das als bekannte Lücke hinnehmen.

## 5) Was sich an bestehendem Verhalten und an der Doku ändern würde

Die Aussage in [04-orchestrierung.md](../04-orchestrierung.md) („IAL und AAL“): *„Wurde nur mit loa2
identifiziert, bleiben auch alle danach eingerichteten Methoden auf loa2 begrenzt“* bekäme eine
Ausnahme. Sie gälte dann nicht mehr uneingeschränkt, sondern „es sei denn, der Inhaber des Kontos
weist sich später auf einem höheren Niveau erneut aus und stimmt der nachträglichen Aufwertung
ausdrücklich zu“. ADR-5 bräuchte einen Nachtrag, der diese eine, eng umrissene Ausnahme von der sonst
geltenden Regel „nie nachträglich“ abgrenzt: freiwillig, vom Inhaber des Kontos ausgelöst,
ausdrücklich abgefragt und weiterhin durch das eigene `maxAcr` jedes Verfahrens begrenzt.

## 6) Offene Fragen und Risiken

1. **Ist die Ausnahme von ADR-5 grundsätzlich gewollt?** Sie weicht bewusst vom bisher streng
   eingehaltenen Grundsatz „nie nachträglich“ ab. Sie betrifft nur Verfahren des eigenen Kontos und
   wirkt nur nach ausdrücklicher, informierter Zustimmung. Trotzdem ändert sie eine Kernregel der
   Sicherheitsarchitektur und sollte nicht nebenbei entschieden werden.
2. **Lücke im Web-Kanal:** Solange die Seite der Required Action keinen Knopf zum Identifizieren hat,
   gibt es den neuen Einstieg nur in der App.
3. **Wie fein die Zustimmung ist:** Werden immer alle betroffenen Verfahren auf einmal aufgewertet,
   oder soll der Nutzer einzelne abwählen können? (Für eine erste Version: alle auf einmal, wie beim
   bestehenden Ja/Nein bei `OfferReIdent` und `DeleteAccountStrategy`.)
4. **Wiederholte Angebote:** Lehnt der Nutzer einmal ab, wird die Frage bei jeder späteren erneuten
   Identifizierung wieder gestellt; einen Schalter „nie wieder fragen“ gibt es nicht. Das ist bewusst
   einfach gehalten, könnte bei häufigen Step-ups aber als lästig empfunden werden.
5. **Aufwand für Tests:** Weil die Prüfung zentral in `ReIdentifyStrategy` sitzt, betrifft sie
   möglicherweise jeden bestehenden Aufrufer. Alle Integrationstests müssten auf ungewollte
   Nebenwirkungen geprüft werden. Eine erste Durchsicht der vorhandenen Testdaten fand keinen Fall, in
   dem ein aktives Verfahren unter dem jeweils erreichten Niveau der Identifizierung liegt; das müsste
   bei der Umsetzung aber bestätigt werden.

## 7) Nächste Schritte (falls die Umsetzung gewünscht ist)

1. Entscheiden lassen, ob die Ausnahme von ADR-5 (Punkt 6.1) so gewollt ist.
2. Neuer Zustand `ReIdentifyState.OfferMethodUpgrade` und Anpassung von `ReIdentifyStrategy`
   (zentrale Prüfung nach `Action.RecordIdentification`).
3. Neue `Action.UpgradeMethods` (`IntentStrategy.kt`), ihre Ausführung in `JourneyService` (Begrenzung
   je Verfahren auf dessen `maxAcr`) und `AccountService.upgradeMethods(...)`.
4. Neuer `ManageAuthMethodsState.IdentifyRequested`, angebunden in `ManageAuthMethodsStrategy` (ohne
   Schwelle `loa2`), dazu ein HTTP-Endpunkt und ein Knopf im Frontend (App-Kanal).
5. Unit-Tests (`ReIdentifyStrategyTest`, `ManageAuthMethodsStrategyTest`, Test für `AccountService`)
   und ein Integrationstest von Anfang bis Ende, der die tatsächliche Anhebung des Niveaus in einer
   folgenden Sitzung nachweist und nicht nur den Wert in der Datenbank prüft.
6. Die Doku anpassen: `04-orchestrierung.md` (Diagramm von `RE_IDENTIFY`, `MANAGE_AUTH_METHODS`, „IAL
   und AAL“) und `12-entscheidungen.md` (Nachtrag zu ADR-5).
