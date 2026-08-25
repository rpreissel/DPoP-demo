# Orchestrierung und Policy

Wie aus dem Ergebnis eines Tools der nächste Prozessschritt wird — und wer entscheidet,
wann genug Faktoren erbracht sind.

Vorausgesetzt wird der `ToolOutcome`-Vertrag aus [03-tool-architektur.md](03-tool-architektur.md).

---

## 1) Vom `ToolOutcome` zum nächsten Prozessschritt

`ToolOutcome.InProgress` übersetzt der Orchestrator mechanisch: `next={type:"tool", toolId:<gleiches Tool>, step:nextStep}` — das Tool bleibt dasselbe. Für `Completed`/`Failed` verlässt die Entscheidung das Tool.

Es gibt bewusst **keine** Adapter-Klasse und keine Registry je Tool: Die Seiteneffekte hängen nicht am konkreten Tool, sondern an seiner Kategorie — `ident-eid` müsste dasselbe tun wie `ident-fsc`. Genau diese Kategorie steckt schon im Typ von `Completed`, deshalb genügt ein zentraler, erschöpfender `when`:

```kotlin
val effectiveAcr = when (outcome) {
    is Identified -> {
        // Account finden/anlegen, dauerhafte Identifikations-Historie anlegen
        outcome.achievedAcr          // die Identifikation begründet das Niveau selbst
    }
    is Enrolled -> {
        // Neue Methode anlegen, inkl. enrolledUnderAcr aus dem aktuellen AuthContext
        outcome.achievedAcr
    }
    is Authenticated -> {
        // Deckelung: eine Methode erzeugt nie mehr Vertrauen als bei ihrer Einrichtung
        minOfAcr(outcome.achievedAcr, usedMethod.enrolledUnderAcr)
    }
}
// Für alle Completed gleich: Nachweis in den AuthContext übernehmen, SessionEvent erzeugen
```

Entscheidungen dahinter:

- Die Deckelung im `Authenticated`-Zweig ist der Grund, warum `enrolledUnderAcr` überhaupt am Methodeneintrag steht ([Abläufe](06-ablaeufe.md) Abschnitt 1): Ein Tool darf melden, welches Niveau es *technisch* erreicht hat, der Orchestrator begrenzt es aber auf das Niveau, unter dem die Methode eingerichtet wurde — sonst ließe sich über eine in schwacher Session hinterlegte Methode dauerhaft ein höheres Niveau erschleichen. Bei `Identified`/`Enrolled` gibt es nichts zu deckeln, dort *entsteht* das Niveau erst.
- `Enrolled` deaktiviert vor dem Anlegen automatisch alle bisher aktiven Einträge derselben `method` — ein erneutes Enrollment **ersetzt** die alte Methode, statt einen zweiten aktiven Eintrag entstehen zu lassen, den die Policy sonst fälschlich doppelt zählen würde.
- Zwei Ebenen für dieselbe Information, bewusst getrennt: `AuthContext.currentAmr` beschreibt **diese Session** und verschwindet mit ihr; `account.identifications` ist **dauerhaft** und beantwortet, mit welchem Verfahren die Identität dieses Accounts überhaupt festgestellt wurde.
- `findOrCreateAccount` findet bei erneuter Identifikation mit derselben KVNR den bestehenden Account wieder. Der kann bereits eine ausreichende Methode haben — deshalb prüft der `Identified`-Zweig `canAccountReach` **vor** der Enrollment-Kandidatenermittlung; sonst bräche der Prozess ab, obwohl der Nutzer nur einen Login-artigen Nachweis erbringen wollte.

Danach ermittelt der Orchestrator `next` als Funktion von `ToolOutcome` **und** dem `ProcessSession`-Zustand — keine statische Tabelle, sondern abhängig von `ProcessPurpose`, bereits erbrachten Nachweisen und bei Step-up zusätzlich von `requiredAcr`/`achievedAcr`:

| ProcessPurpose | toolId | ToolOutcome | Zusätzliche Bedingung | nächster `next` |
|---|---|---|---|---|
| REGISTRATION | `ident-*` | Completed | `canAccountReach` -> true (wiedergefundener Account reicht bereits) | wie LOGIN/STEP_UP „Policy fordert weiteren Faktor" — Angebot ist `candidateTools`, **kein** Enrollment |
| REGISTRATION | `ident-*` | Completed | `canAccountReach` -> false | ein/mehrere Enrollment-Kandidaten -> Tool direkt bzw. Auswahlseite |
| REGISTRATION | `enroll-*` | Completed | `AuthPolicy.isSatisfied` -> true | `authenticated` |
| REGISTRATION | `enroll-*` | Completed | Policy fordert weiteren Faktor | ein/mehrere Kandidaten -> Tool direkt bzw. Auswahlseite |
| LOGIN / STEP_UP | `auth-*` | Completed | `isSatisfied` -> true | `authenticated`; bei STEP_UP zusätzlich `ChannelSession.state` zurück auf `AUTHENTICATED` |
| LOGIN / STEP_UP | `auth-*` | Completed | Policy fordert weiteren Faktor | ein/mehrere Kandidaten -> Tool direkt bzw. Auswahlseite |
| LOGIN / STEP_UP | `auth-*` | Completed | **kein** Kandidat verfügbar | Prozessabbruch (`410`) — Niveau mit vorhandenen Methoden nicht erreichbar |
| MANAGE_METHODS | `enroll-*` | Completed | — (kein Policy-Check, Abschnitt 3) | `authenticated`; Kanal bleibt/wird `AUTHENTICATED` unabhängig vom erreichten Niveau |
| beliebig | beliebig | Failed | `retryCount` unter Limit | `200`, `next` auf dasselbe Tool, `stepData.error` |
| beliebig | beliebig | Failed | Retry-Limit erreicht | Prozessabbruch (`410`) |

Retry-Regel: Ein fehlgeschlagener Versuch mit verbleibenden Retries ist **kein** HTTP-Fehlerfall, sondern verhält sich wie fehlende Eingabe (`200` plus Navigation, Grund in `stepData.error`). Erst bei erschöpften Retries endet der Prozess terminal (`410`) — HTTP-Fehlercodes signalisieren gestörte Abläufe, nicht erwartbare Eingabefehler ([API](05-api.md)).

---

## 2) AuthPolicy: Mehr-Faktor-Entscheidung

Nach jedem abgeschlossenen Tool fragt die `next`-Tabelle die `AuthPolicy`, statt unbedingt abzuschließen — sie ist die einzige Stelle, die weiß, was genug ist.

```kotlin
interface AuthPolicy {
    fun isSatisfied(evidence: AuthEvidence, requiredAcr: String): Boolean
    fun candidateTools(evidence: AuthEvidence, requiredAcr: String, account: Account): List<String>
    fun reIdentCandidates(evidence: AuthEvidence, requiredAcr: String): List<String>  // Abschnitt 3
    fun canAccountReach(account: Account, requiredAcr: String): Boolean
    fun enrollmentCandidates(account: Account, requiredAcr: String): List<String>
    fun resolveAcr(evidence: AuthEvidence): String
}
```

Zwei Bedingungen müssen zusammen erfüllt sein:

1. **Niveau**: `resolveAcr(evidence) >= requiredAcr`. Die Abbildung von `amr`-Kombinationen auf `acr`-Werte ist fachlich/regulatorisch offen und hier bewusst nicht endgültig festgeschrieben. RFC 8176 definiert zwar eine IANA-Registry für `amr`-Werte (`pwd`, `otp`, `hwk`/`swk`, `user`, `face`, `fpt`, `mfa`, ...), aber welche Kombination welches Vertrauensniveau (eIDAS/BSI/NIST je nach Kontext) ergibt, ist damit noch nicht festgelegt — das bleibt die hier offen gehaltene Politik-Entscheidung. Die `amr`-Strings dieses Projekts (`sms`, `password`, `email`, `fsc`, `device`, `pin`, `biometric`) folgen deshalb der eigenen, methodennamen-nahen Konvention statt RFC 8176 direkt zu übernehmen.
2. **Faktorvielfalt**: Für MFA-Stufen mindestens zwei **verschiedene** Faktorarten — gezählt wird die Vereinigung der `factorTypes` über alle abgeschlossenen Tools, nie die Tool-Anzahl. Ein einzelnes Tool, das selbst schon zwei Faktorarten meldet (z. B. ein Passkey mit User Verification), erfüllt MFA im Alleingang.

Wichtige Einschränkung: Ein Tool darf nur Faktoren melden, die es dem Server gegenüber tatsächlich **nachweisen** kann. Eine nur lokal geprüfte App-PIN schützt das Gerät, nicht die Anfrage — dafür gehört nur `{possession}` in den Descriptor.

### Registrierung: Session-Nachweis ist nicht gleich Account-Fähigkeit

Beim Login lautet die Frage „reicht das *jetzt*?" (`isSatisfied`). Bei der Registrierung lautet sie „kommt der Nutzer damit *künftig wieder herein*?" (`canAccountReach`) — verschiedene Fragen, weil ein Identifikationsverfahren keine dauerhafte Auth-Methode ist: `ident-fsc` zählt zwar für `AuthContext.currentFactorTypes` dieser Session, landet aber in `account.identifications`, nicht in `account.authenticationMethods`. Fordert der Kanal ein MFA-Niveau, muss die Registrierung deshalb so lange Enroll-Tools durchlaufen, bis `canAccountReach` erfüllt ist.

Daraus folgt eine Deckelungskette über drei Stufen: `identifications[].loa` begrenzt, was ein Account überhaupt je erreichen kann; `authenticationMethods[].enrolledUnderAcr` begrenzt, was eine einzelne Methode liefern darf; `Completed.achievedAcr` meldet, was der konkrete Durchlauf erreicht hat. Praktische Folge: Ein Kanal, der `loa3` verlangt, braucht bereits ein `loa3`-fähiges Ident-Verfahren — wurde nur mit `loa2` identifiziert, bleiben auch alle danach eingerichteten Methoden auf `loa2` gedeckelt. Deshalb ist `requiredAcr` schon beim Anlegen des Kanals setzbar ([API](05-api.md)).

Kandidatenermittlung: nur aktive Methoden, deren `enrolledUnderAcr` das geforderte Niveau zulässt und deren `maxAcr` die Lücke schließen könnte; bereits in dieser Session verwendete Methoden (und bei MFA-Bedarf: bereits erbrachte Faktorarten) fallen heraus. Bei genau einem Kandidaten überspringt der Orchestrator die Auswahlseite ([API](05-api.md)).

### Required Actions: mehr als nur ACR-Sufficiency

Registrierung kann nicht abschließen, sobald `canAccountReach`/`isSatisfied` grün sind — es gibt zusätzlich eine geordnete Liste von **Required Actions** (Keycloak-Begriff: pro Nutzer abzuarbeitende Pflicht-Schritte wie `VERIFY_EMAIL`, die vor Abschluss der Session erledigt sein müssen). Die Verallgemeinerung dahinter: die ACR-Sufficiency-Prüfung selbst ist bereits die erste Required Action ("ausreichendes Login-Verfahren eingerichtet") — `RequiredAction` (`orchestrator/policy/RequiredAction.kt`) ist ein kleines Interface (`isSatisfied`, `candidates`), `SufficientLoginMethodRequiredAction` delegiert dafür 1:1 an `AuthPolicy`. Für REGISTRATION kommt `ConfirmedEmailRequiredAction` hinzu: ein Account ohne bestätigte E-Mail könnte sonst über einen einzigen `loa1`-Faktor (z. B. `sms`) fertig registrieren und `enroll-password` (das eine bestätigte E-Mail als Identifikator braucht) dauerhaft nie erreichen.

Beide Required Actions sind bewusst aus vorhandenem Zustand **abgeleitet** (`authenticationMethods`, `emailConfirmedAt`), nicht als eigenes, am Account gespeichertes Feld — eine gespeicherte Liste würde nur Drift-Risiko einführen, ohne dass aktuell eine Required Action existiert, die das bräuchte. Ein späteres, nicht-ableitbares Required Action (z. B. eine administrativ zugewiesene Einzelaufgabe) bekäme eine eigene `RequiredAction`-Implementierung mit echtem Account-Feld, ohne dass sich das Interface oder die bestehenden zwei Implementierungen ändern müssten.

Scope-Grenze: Die Required-Action-Liste ist **prozessabhängig** — nur `RegistrationProcessSession` bekommt `ConfirmedEmail` zusätzlich; `StepUpProcessSession`/`LoginProcessSession` prüfen weiterhin nur `SufficientLoginMethod` (unverändertes Verhalten). `MANAGE_METHODS` konsultiert die Liste gar nicht (Abschnitt 3: ein einziges `Enrolled` beendet dort immer sofort). Bereits bestehende Accounts ohne bestätigte E-Mail werden dadurch **nicht** rückwirkend von LOGIN/STEP_UP ausgeschlossen.

---

## 3) MANAGE_METHODS: freiwillige Methodenverwaltung ohne Policy-Ziel

`ProcessPurpose.MANAGE_METHODS` erlaubt einem bereits `AUTHENTICATED`-Kanal, freiwillig Auth-Mittel hinzuzufügen oder zu deaktivieren — losgelöst von der policy-getriebenen REGISTRATION/STEP_UP-Schleife. Endpunkte siehe [API](05-api.md), Abschnitt „Methoden verwalten".

Wesentlicher Unterschied zu REGISTRATION: Der Abschluss hängt **nicht** von `canAccountReach`/`isSatisfied` ab — ein einziges erfolgreiches `Enrolled` beendet den Prozess sofort zurück zu `AUTHENTICATED`, unabhängig vom erreichten Niveau. `DELETE .../methods/{method}` lehnt dagegen ab (`409`), falls der Account danach das kanaleigene `requiredAcr` nicht mehr erreichen könnte.

### loa2-Freigabe-Gate und der Ein-Methoden-Fall

Beide Endpunkte verlangen zuerst, dass die aktuelle Session bereits `loa2` nachweist — sonst wird stattdessen ein Step-up verlangt. Begründung: dieselbe Anti-Selbsteskalations-Logik wie die `enrolledUnderAcr`-Deckelung (Abschnitt 1); eine gekaperte `loa1`-Session darf nicht aus eigener Kraft Credentials hinzufügen oder entfernen können.

Das erzeugte zunächst eine echte Sackgasse: Ein Account mit genau **einer** aktiven Auth-Methode hatte nach einem frischen geräte-gebundenen Login (der über `DeviceAccountLink` direkt bei LOGIN landet und dabei nur `loa1` nachweist) keinen Weg, `loa2` je zu erreichen — es gab keine zweite Methode zum Kombinieren. Lösung: `AuthPolicy.reIdentCandidates` bietet für genau diesen Fall Re-Identifikation (`ident-fsc`) als alternativen Weg an — `ident-fsc` erreicht `loa2` bereits im Alleingang, unabhängig davon, was der Account sonst enrollt hat. Bewusst eine eigene Methode statt Teil von `candidateTools`: Re-Identifikation soll nie als generische Login-Abkürzung erscheinen, nur als Notausgang aus dieser einen Sackgasse. Während eines so ausgelösten Step-ups muss die neu identifizierte Person zum bereits angemeldeten Account passen (`409` bei Abweichung) — sonst könnte eine `loa1`-Session eine fremde Identität einschleusen und einen anderen Account kapern.

---

## 4) Lookup-basierter Login ("Login ohne DPoP")

Löst das strukturelle Problem, dass ein nicht über `DeviceAccountLink` verlinktes Gerät bisher nur REGISTRATION angeboten bekam — kein Weg, sich ohne vorherige Geräte-Bindung in einen bestehenden Account einzuloggen (klassischer Web-Login: nur Identifier + Credential, kein gepaartes Gerät nötig).

Der optionale `intent`-Parameter bei `POST /channels` ([API](05-api.md)) steuert das: `intent="login"` unterdrückt den `DeviceAccountLink`-Lookup und ruft `startLookupLogin` statt der gewöhnlichen Kanal-Initialisierung auf, die den fixen Werkzeugsatz der `-lookup`-Tools direkt anbietet — **nicht** über `AuthPolicy.candidateTools`, das einen bereits aufgelösten Account braucht, den es an dieser Stelle noch gar nicht geben kann. Der Prozess bleibt eine gewöhnliche `LoginProcessSession`.

Die `-lookup`-Tools lösen den Account selbst über die eingegebene E-Mail auf und melden ihn über `Completed.Authenticated.accountId` zurück ([Tool-Architektur](03-tool-architektur.md) Abschnitt 2). `auth-email-lookup` tut das im Handler, weil `auth_email` als deklarierte Ausnahme auf `account` zugreifen darf; `auth-sms-lookup`/`auth-password-lookup` bekommen die aufgelöste `accountId` weiterhin vom Controller gereicht — sie brauchen nur einen opaken Konto-Handle, nicht die E-Mail-Semantik. Warum sie trotz gleicher `method` nicht mit ihrem geräte-gebundenen Zwilling kollidieren, steht dort ebenfalls (`MethodRole.LOOKUP_AUTH` gegenüber `DEVICE_AUTH`). Bei Erfolg schreibt der Orchestrator `DeviceAccountLink` neu — ein danach mit `intent="auto"` angelegter Kanal erkennt das Gerät und bietet den gewöhnlichen geräte-gebundenen LOGIN an, nicht wieder den Lookup-Login.

**Enumeration-Schutz**: Eine unbekannte E-Mail liefert exakt dieselbe Antwortform wie ein korrekt aufgelöster Account mit falschem Credential — nie eine eigene Fehlerform, auch nicht im Timing der demo-Werte ([API](05-api.md)). Bewusst nicht weiter gehärtet (kein künstliches Timing-Padding) — für eine Demo ausreichend, in einem Produktivsystem der nächste Schritt.
