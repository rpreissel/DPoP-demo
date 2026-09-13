# Idee: Methoden-Upgrade nach erneuter/erstmaliger Identifizierung (RE_IDENTIFY)

Status: **Konzept, nicht umgesetzt**. Beschreibt, was es bedeuten würde, neben "Methoden
verwalten" (App/Web) einen expliziten "Identifizieren"-Trigger anzubieten und zentral, nach
jeder erfolgreichen RE_IDENTIFY-Sub-Journey, zu prüfen, ob bestehende Anmeldeverfahren mit
niedrigerem `enrolledUnderAcr` nach Zustimmung aufgewertet werden dürfen.

---

## 1) Ausgangslage

`authenticationMethods[].enrolledUnderAcr` ist im DPoP-demo-Projekt bewusst **für immer
eingefroren** — die "dreifache Deckelung" (ADR-5, [12-entscheidungen.md](../12-entscheidungen.md),
[06-ablaeufe.md](../06-ablaeufe.md) #1): eine Methode darf bei der Authentifizierung nie mehr
Vertrauen erzeugen, als bei ihrer Einrichtung vorhanden war. Ohne diese Regel könnte, wer eine
schwache Session übernimmt, dort eine eigene Methode hinterlegen und damit dauerhaft ein höheres
Niveau erreichen, als er je nachgewiesen hat.

Das erzeugt aber ein reales Nutzerproblem: Wer sich z. B. über "Enrollment zuerst"
(`RegisterEnrollFirstStrategy`) nur mit SMS registriert (`sms.enrolledUnderAcr = loa1`, `personId
== null`) und sich später ganz regulär per `ident-fsc`/`ident-eid` identifiziert, bleibt trotzdem
für immer auf `loa1` gedeckelt — obwohl der Kontoinhaber seine Identität inzwischen nachweislich
stärker bestätigt hat. Anders als beim ursprünglichen Angriffsszenario ist hier nicht ein
Angreifer am Werk, sondern der Kontoinhaber selbst, der gerade freiwillig einen stärkeren Nachweis
erbracht hat.

## 2) Kernidee

1. **Expliziter Trigger** — ein neuer Button "Identifizieren" neben "Methoden verwalten" (App
   zuerst, Web als bekannte Lücke, siehe Abschnitt 6), der jederzeit die bereits geteilte
   `RE_IDENTIFY`-Sub-Journey startet (`ReIdentifyStrategy`) — unabhängig davon, ob `personId`
   schon gesetzt ist oder nicht, und **ohne** das sonst übliche loa2-Gate: die Re-Identifizierung
   selbst ist der Mechanismus, der Vertrauen erhöht, ein Gate davor wäre widersinnig.
2. **Zentrale Prüfung, für JEDEN Aufrufer** — nicht nur für den neuen Button, sondern für alle
   heutigen `RE_IDENTIFY`-Aufrufer (`STEP_UP`, `LOOKUP_LOGIN`, `AuthEnrollCore`/REGISTER,
   `RegisterEnrollFirstStrategy`): sobald eine Identifizierung erfolgreich abschließt, prüfen, ob
   aktive Methoden mit niedrigerem `enrolledUnderAcr` existieren. Wenn ja: den Nutzer fragen, mit
   Aufzählung der betroffenen Methoden, ob diese künftig bis zum neu erreichten Niveau zählen
   dürfen. Wenn keine betroffen sind, läuft alles wie bisher unverändert weiter (kein
   zusätzlicher Schritt, keine Verhaltensänderung für den Normalfall).
3. **Zustimmung → Anheben** — bei "Ja" wird `enrolledUnderAcr` der genannten Methoden angehoben,
   gedeckelt durch die eigene `maxAcr` des jeweiligen Tools (eine Methode darf nie mehr können,
   als sie technisch je nachweisen könnte). Das ist eine bewusste, **zustimmungsgebundene**
   Ausnahme von ADR-5, nicht dessen Aufhebung: Auslöser ist immer ein frischer, erfolgreicher
   Identitätsnachweis des Kontoinhabers selbst, nie eine automatische oder stille Änderung. Bei
   "Nein" bleibt alles wie es ist.

## 3) Wo das technisch ansetzen würde

`RE_IDENTIFY` ([`ReIdentifyStrategy.kt`](../../src/main/kotlin/com/example/dpop/orchestrator/journey/strategy/ReIdentifyStrategy.kt),
Zustände in `ReIdentifyState.kt`) ist nie ein Entry-Intent, sondern wird ausschließlich über
`Transition.RequireSubJourney` erreicht. Der `Identifying`-Zustand läuft heute nach einem
erfolgreichen `Completed(Identified)` direkt in `Action.ConfirmIdentity` und danach unbedingt in
`Transition.Authenticated`. Genau an dieser Stelle — nachdem `ConfirmIdentity` bereits gelaufen
ist, aber bevor `Transition.Authenticated` zurückgegeben wird — müsste ein neuer, dritter Zustand
(`OfferMethodUpgrade`, ein `AnswerableState` wie das bereits bestehende `OfferReIdent`) eingefügt
werden, der die betroffenen Methoden auflistet und fragt.

Weil dieser Punkt **innerhalb** von `ReIdentifyStrategy` liegt, würde die Prüfung transparent für
alle fünf Aufrufer gelten (STEP_UP, LOOKUP_LOGIN, AuthEnrollCore, RegisterEnrollFirstStrategy, neuer
Manage-Trigger), ohne dass einer von ihnen selbst geändert werden müsste — nur der neue,
gate-lose `IdentifyRequested`-Zustand in `ManageAuthMethodsStrategy`/`ManageAuthMethodsState`
müsste als sechster Aufrufer neu hinzukommen.

Bei Zustimmung bräuchte es:
- eine neue `Action` (`Action.UpgradeMethods(accountId, methodInstanceIds, newAcr)`), die
  `JourneyService` ausführt (Deckelung pro Methode auf deren eigene Tool-`maxAcr` dort, da
  `AccountService` bewusst nichts von `tool_spi` weiß),
- eine neue Persistenz-Methode `AccountService.upgradeMethods(...)`, im selben Muster wie das
  bestehende `deactivateAuthenticationMethod` (Methoden anhand ihrer `id` treffen, nicht anhand
  des Methodennamens — mehrere aktive Instanzen desselben Verfahrens, z. B. Geräte, dürfen nicht
  verwechselt werden).

`ToolOutcome.Completed.Identified.achievedAcr` wird von beiden Handlern verlässlich gesetzt
(`IdentFscToolHandler.kt:77`, `IdentEidToolHandler.kt:72`: `achievedAcr = descriptor.maxAcr`,
also `loa2` bzw. `loa3`) — insofern kein Stolperstein. Für "das gerade erreichte Niveau" in der
neuen Prüfung sollte trotzdem nicht `state.targetAcr` verwendet werden: das ist nur die
*Mindestanforderung*, mit der die Sub-Journey gestartet wurde — beim neuen, gate-losen
Manage-Trigger z. B. bewusst `loa1`, damit `ident-fsc` als Kandidat nicht herausgefiltert wird —
und kann damit niedriger sein als das tatsächlich erreichte Niveau. Robuster ist `ctx.currentAcr`
(`JourneyContext.currentAcr = policy.resolveAcr(evidence, account)`) an der Stelle, an der
`Identifying`s `ActionCompleted` nach dem `ConfirmIdentity`-Perform wieder eintrifft — zu diesem
Zeitpunkt hat `recordToolCompletion` die neue `MethodEvidence` (mit `loa = achievedAcr`) bereits
angewendet, und der Kontext für den Folgeaufruf wird frisch aufgebaut, sodass `ctx.currentAcr`
exakt das erreichte Niveau widerspiegelt.

## 4) Frontend

- **App-Kanal**: `AuthenticationCompletedView.tsx` zeigt "Anmeldeverfahren verwalten" bereits mit
  Add/Remove-Buttons und dem `enrolledUnderAcr`-Deckel pro Methode an — ein "Identifizieren"-Button
  würde sich dort naturgemäß einreihen. Der generische `Prompt.Confirm`-Screen, den die App für
  jeden `AnswerableState` schon rendert, würde `OfferMethodUpgrade` ohne weitere
  Frontend-Änderung korrekt anzeigen (reiner Text aus `stepData.prompt`).
- **Web-Kanal**: "Anmeldeverfahren verwalten" ist dort heute **nicht** orchestrator-gesteuert,
  sondern leitet auf Keycloaks eigene Account-Management-UI um. Der neue HTTP-Endpunkt wäre
  kanal-unabhängig, ein Web-Trigger also rein eine spätere Frontend-Ergänzung, keine
  Backend-Änderung — aber eben (noch) nicht vorhanden. Für eine erste Umsetzung müsste das als
  bekannte Lücke akzeptiert oder die Web-Ansicht für "Methoden verwalten" grundsätzlich vom
  Keycloak-Redirect auf eine eigene, orchestrator-gesteuerte Seite umgestellt werden — ein
  deutlich größerer Umbau, der über diese Idee hinausgeht.

## 5) Was sich an bestehendem Verhalten/Dokumentation ändern würde

Die Aussage in [04-orchestrierung.md](../04-orchestrierung.md) ("IAL und AAL"): *"wurde nur mit
loa2 identifiziert, bleiben auch alle danach eingerichteten Methoden auf loa2 gedeckelt"* würde
eine Ausnahme bekommen — nicht mehr uneingeschränkt wahr, sondern "es sei denn, der Kontoinhaber
identifiziert sich später erneut auf einem höheren Niveau und stimmt der rückwirkenden Aufwertung
ausdrücklich zu". ADR-5 selbst bräuchte einen Nachtrag, der diese eine, eng umrissene Ausnahme
(freiwillig, konto­inhaber-ausgelöst, explizit abgefragt, weiterhin durch die eigene `maxAcr`
jeder Methode gedeckelt) von der sonst geltenden "nie rückwirkend"-Regel abgrenzt.

## 6) Offene Fragen und Risiken

1. **Ist die Ausnahme von ADR-5 grundsätzlich gewollt?** Das ist eine bewusste Abweichung vom
   bisher strikt eingehaltenen "nie rückwirkend"-Prinzip. Sie betrifft nur Methoden des eigenen
   Kontos und wird nur nach explizitem, informierten Opt-in wirksam — trotzdem ändert sie ein
   Kern-Invariant der Sicherheitsarchitektur und sollte nicht "nebenbei" entschieden werden.
2. **Web-Kanal-Lücke**: Ohne Umbau der Web-"Methoden verwalten"-Seite (aktuell reiner
   Keycloak-Redirect) bliebe der neue Trigger vorerst App-only.
3. **Granularität der Zustimmung**: Immer "alle betroffenen Methoden auf einmal" hochstufen, oder
   sollte der Nutzer einzelne Methoden abwählen können? (Für eine erste Version: alle auf einmal,
   analog zum bestehenden Ja/Nein-Muster bei `OfferReIdent`/`DeleteAccountStrategy`.)
4. **Wiederholte Angebote**: Lehnt der Nutzer einmal ab, wird die Frage bei jeder künftigen
   erneuten Identifizierung erneut gestellt (kein "nie wieder fragen"-Flag vorgesehen) — bewusst
   einfach gehalten, könnte bei häufigen Step-ups aber als redundant empfunden werden.
5. **Testaufwand**: Da die Prüfung zentral in `ReIdentifyStrategy` sitzt, betrifft sie potenziell
   jeden bestehenden Aufrufer — die volle Integrationstest-Suite müsste auf unbeabsichtigte
   Nebeneffekte geprüft werden (in einer ersten Durchsicht der aktuellen Test-Fixtures wurde kein
   bestehender Fall gefunden, bei dem eine aktive Methode unterhalb des jeweils erreichten
   Identifikationsniveaus liegt — müsste aber bei Umsetzung verifiziert werden).

## 7) Nächste Schritte (falls Umsetzung gewünscht)

1. Entscheidung einholen, ob die ADR-5-Ausnahme (Punkt 6.1) so gewollt ist.
2. Neuer Zustand `ReIdentifyState.OfferMethodUpgrade` + Anpassung von `ReIdentifyStrategy`
   (zentrale Prüfung nach `ConfirmIdentity`).
3. Neue `Action.UpgradeMethods` (`IntentStrategy.kt`) + Ausführung in `JourneyService` (Deckelung
   pro Methode auf deren `maxAcr`) + `AccountService.upgradeMethods(...)`.
4. Neuer `ManageAuthMethodsState.IdentifyRequested` + Verdrahtung in `ManageAuthMethodsStrategy`
   (kein loa2-Gate) + HTTP-Endpunkt + Frontend-Button (App-Kanal).
5. Unit-Tests (`ReIdentifyStrategyTest`, `ManageAuthMethodsStrategyTest`,
   `AccountService`-Test) und ein Ende-zu-Ende-Integrationstest, der die tatsächliche
   Niveau-Anhebung über eine Folgesession nachweist (nicht nur den DB-Wert prüft).
6. Doku-Updates: `04-orchestrierung.md` (RE_IDENTIFY-Diagramm, MANAGE_AUTH_METHODS, "IAL und AAL"),
   `12-entscheidungen.md` (ADR-5-Nachtrag).
