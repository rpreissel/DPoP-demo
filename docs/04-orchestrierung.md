# Orchestrierung und Policy

Wie aus dem Ergebnis eines Tools der nächste Prozessschritt wird — und wer entscheidet,
wann genug Faktoren erbracht sind.

Vorausgesetzt wird der `ToolOutcome`-Vertrag aus [03-tool-architektur.md](03-tool-architektur.md).

---

## 1) Vom `ToolOutcome` zum nächsten Prozessschritt

`ToolOutcome.InProgress` übersetzt der Orchestrator mechanisch und ohne weitere Bedingungen: `next={type:"tool", toolId:<gleiches Tool>, step:nextStep}` — das Tool bleibt dasselbe.

Für `Completed`/`Failed` verlässt die Entscheidung das Tool.

### Verarbeitung von `Completed`

Es gibt bewusst **keine** Adapter-Klasse und keine Registry je Tool: Die Seiteneffekte hängen nicht am konkreten Tool, sondern an seiner Kategorie — `ident-eid` müsste dasselbe tun wie `ident-fsc`, `enroll-passkey` dasselbe wie `enroll-sms`. Genau diese Kategorie steckt schon im Typ von `Completed`, deshalb genügt ein zentraler, erschöpfender `when` im Orchestrator:

```kotlin
// Methode kommt aus der Selbstauskunft des Moduls, nicht aus dem toolId-String
val method = toolRegistry.descriptorOf(toolId).method

// 1. Kategoriespezifischer Seiteneffekt; liefert das anrechenbare Niveau
val effectiveAcr = when (outcome) {

    is Identified -> {
        processSession.personId = outcome.personId
        processSession.accountId = account.findOrCreateAccount(outcome.personId)
        // dauerhafte Historie: mit welchem Verfahren, Niveau und Nachweis wurde identifiziert
        account.addIdentification(
            processSession.accountId, method, outcome.achievedAcr, outcome.auditDetails
        )
        outcome.achievedAcr          // die Identifikation begründet das Niveau selbst
    }

    is Enrolled -> {
        account.addAuthenticationMethod(
            processSession.accountId,
            outcome.enrollmentRef,
            // Bedingungen der Einrichtung — kennt nur der Orchestrator, nicht das Modul
            enrolledUnderAcr = authContext.currentAcr,
            details = outcome.auditDetails.orEmpty() + mapOf(
                "enrolledUnderAmr" to authContext.currentAmr,
                "channel" to channelSession.channel
            )
        )
        outcome.achievedAcr
    }

    is Authenticated -> {
        // Deckelung: eine Methode erzeugt nie mehr Vertrauen als bei ihrer Einrichtung vorhanden war
        val usedMethod = account.findActiveMethod(processSession.accountId, method)
        minOfAcr(outcome.achievedAcr, usedMethod.enrolledUnderAcr)
    }
}

// 2. Für alle Completed gleich: Nachweis übernehmen und auditieren
authContext.addAmr(outcome.amr)                 // ein Durchlauf kann mehrere amr-Werte liefern
authContext.addFactorTypes(outcome.factorTypes)
effectiveAcr?.let { authContext.currentAcr = it }
sessionEvents.record(processSession, outcome)

// 3. Danach erst: next ermitteln (Tabelle unten)
```

- Schritt 2 zeigt, warum `Authenticated` kein Leerfall ist: Jeder erfolgreiche Tool-Abschluss vermerkt seine Methode im `AuthContext` (sichtbar für den Client über `GET /channels/...` als `currentAmr`/`currentAcr`), aktualisiert bei Step-up `achievedAcr` und erzeugt einen `SessionEvent`. Nur der *account-bezogene* Teil in Schritt 1 unterscheidet sich je Kategorie.
- Die Deckelung im `Authenticated`-Zweig ist der Grund, warum `enrolledUnderAcr` am Methodeneintrag steht (Datenmodell in [06-ablaeufe.md](06-ablaeufe.md)): Ein Tool darf melden, welches Niveau es *technisch* erreicht hat, aber der Orchestrator begrenzt es auf das Niveau, unter dem die verwendete Methode eingerichtet wurde. Andernfalls ließe sich über eine in schwacher Session hinterlegte Methode dauerhaft ein höheres Niveau erschleichen. Bei `Identified` und `Enrolled` gibt es nichts zu deckeln — dort *entsteht* das Niveau gerade erst.
- `Enrolled` deaktiviert vor dem Anlegen automatisch alle bisher aktiven Einträge derselben `method` (`AccountService.addAuthenticationMethod`: `account.authenticationMethods.filter { it.method == method && it.active }.forEach { it.active = false }`). Ein erneutes Enrollment — etwa eine neu bestätigte E-Mail-Adresse, nachdem bereits einmal `enroll-email` durchlaufen wurde — **ersetzt** die alte Methode, statt sie zu verdecken. Ohne diese Regel könnten zwei gleichzeitig aktive Einträge derselben Methode entstehen, die `canAccountReach`/`candidateTools`/`resolveAcr` (Abschnitt 2) fälschlich doppelt zählen würden.
- Bei `method=="email"` entnimmt der Orchestrator vorab `auditDetails["email"]` und ruft `AccountService.confirmEmail(accountId, email)`, bevor der Rest von `auditDetails` in `authenticationMethods[].details` landet — die E-Mail selbst wird dabei aus diesem Blob entfernt. Grund: Die bestätigte E-Mail ist ein first-class Feld auf `Account` (`email`/`emailConfirmedAt`, [Abläufe](06-ablaeufe.md) Abschnitt 1), keine Dopplung im generischen Audit-`details`.
- Zwei Ebenen für dieselbe Information, bewusst getrennt: `AuthContext.currentAmr` beschreibt **diese Session** und verschwindet mit ihr — ein späterer reiner SMS-Login hat korrekt nur `["sms"]`. `account.identifications` dagegen ist **dauerhaft** und beantwortet, mit welchem Verfahren und Vertrauensniveau die Identität dieses Accounts überhaupt einmal festgestellt wurde. Ohne diesen Eintrag wäre die Herkunft der Identität nach Ablauf der Audit-Frist ([Betrieb](07-betrieb.md)) nicht mehr feststellbar.
- `findOrCreateAccount` ([API](05-api.md) #2) findet bei erneuter Identifikation mit derselben KVNR den bestehenden Account wieder, statt einen zweiten anzulegen. Dieser Account kann bereits eine aktive Methode haben, die `requiredAcr` erreicht — dann gäbe es nichts zu enrollen, und `enrollmentCandidates` liefert erwartungsgemäß eine leere Liste. Die `Identified`-Zeile prüft deshalb `canAccountReach` **vor** `enrollmentCandidates`; andernfalls bräche der Prozess mit `410 PROCESS_ABORTED` ab, obwohl der Nutzer sich nur erneut identifiziert hat und eigentlich einen Login-artigen Nachweis der vorhandenen Methode erbringen sollte.
- Ein künftiges Tool muss sich in eine der drei `Completed`-Varianten einfügen. Braucht es wirklich eine vierte Ergebnisart, ist das ein sichtbarer, zentraler Eingriff (neue Variante + neuer `when`-Zweig) statt eines still hinzugefügten Adapters — bei einem Auth-System eher erwünscht.
- Die `next`-Tabelle unten arbeitet danach nur noch mit normierten Größen (`ProcessPurpose`, `accountId`/`personId`, ACR-Vergleich) und bleibt für jedes Tool gleich.

Erst danach ermittelt der Orchestrator `next` als Funktion von `ToolOutcome` **und** dem aktuellen Zustand der `ProcessSession` — **keine statische Tabelle**, sondern abhängig von `ProcessPurpose`, bereits erlaubten/gewählten Methoden und bei Step-up zusätzlich von `requiredAcr`/`achievedAcr`:

| ProcessPurpose | toolId | ToolOutcome | Zusätzliche Bedingung (Session-Zustand) | nächster `next` |
|---|---|---|---|---|
| REGISTRATION | `ident-*` | Completed | `AuthPolicy.canAccountReach(account, requiredAcr)` -> true (per `findOrCreateAccount` wiedergefundener Account hat bereits eine ausreichende Methode) | `{type:"tool"/"flow", ...}` wie bei LOGIN/STEP_UP „Policy fordert weiteren Faktor" — Angebot ist `candidateTools`, **kein** Enrollment |
| REGISTRATION | `ident-*` | Completed | `canAccountReach` -> false, genau eine erlaubte Enrollment-Methode | `{type:"tool", toolId:"enroll-sms", step:"enroll"}` |
| REGISTRATION | `ident-*` | Completed | `canAccountReach` -> false, mehrere erlaubte Enrollment-Methoden | `{type:"flow", context:"enrollment", step:"selectMethod"}` plus `stepData={options:[...]}` |
| REGISTRATION | `enroll-*` | Completed | `AuthPolicy.isSatisfied(...)` -> true | `{type:"flow", context:"authentication", step:"authenticated"}` |
| REGISTRATION | `enroll-*` | Completed | Policy fordert weiteren Faktor, genau ein Kandidat | `{type:"tool", toolId:<Kandidat>, step:<Startschritt>}` |
| REGISTRATION | `enroll-*` | Completed | Policy fordert weiteren Faktor, mehrere Kandidaten | `{type:"flow", context:"enrollment", step:"selectMethod"}` plus `stepData={options:[Kandidaten]}` |
| LOGIN / STEP_UP | `auth-*` | Completed | `AuthPolicy.isSatisfied(...)` -> true | `{type:"flow", context:"authentication", step:"authenticated"}`; bei STEP_UP `ChannelSession.state` zurück auf `AUTHENTICATED` |
| LOGIN / STEP_UP | `auth-*` | Completed | Policy fordert weiteren Faktor, genau ein Kandidat | `{type:"tool", toolId:<Kandidat>, step:<Startschritt>}` |
| LOGIN / STEP_UP | `auth-*` | Completed | Policy fordert weiteren Faktor, mehrere Kandidaten | `{type:"flow", context:"auth", step:"selectMethod"}` plus `stepData={options:[Kandidaten]}` |
| LOGIN / STEP_UP | `auth-*` | Completed | Policy fordert weiteren Faktor, **kein** Kandidat verfügbar | Prozessabbruch (`ProcessState=FAILED`, HTTP `410`) — das geforderte Niveau ist mit den vorhandenen Methoden nicht erreichbar |
| MANAGE_METHODS | `enroll-*` | Completed | — (kein Policy-Check, siehe Abschnitt 3) | `{type:"flow", context:"authentication", step:"authenticated"}`; Kanal bleibt/wird `AUTHENTICATED`, unabhängig vom erreichten Niveau. Ein weiteres Mittel erfordert einen erneuten `POST .../methods`-Aufruf ([API](05-api.md)) |
| beliebig | beliebig | Failed | `retryCount` unter Limit | HTTP `200`, `next={type:"tool", toolId:<gleiches Tool>, step:<Startschritt>}` plus `stepData={error:<reason>, ...}` |
| beliebig | beliebig | Failed | `retryCount` erreicht/überschritten | Prozessabbruch (`ProcessState=FAILED`, HTTP `410`) |

Diese Zeilen sind Beispielregeln für die Bandbreite der Entscheidung, keine abschließende, rein statische Zuordnung. Handler und Module kennen diese Logik nicht; sie liefern ausschließlich `ToolOutcome`.

Retry-Regel: Ein fehlgeschlagener Versuch mit verbleibenden Retries ist **kein** HTTP-Fehlerfall. Er verhält sich wie `INPUT_REQUIRED` — der Nutzer soll erneut eingeben — und wird deshalb wie dort mit `200` plus Navigation beantwortet; der Grund steht in `stepData.error`. Erst wenn keine Versuche mehr übrig sind, wird der Prozess terminal beendet (`410`). Das hält die Regel aus [API](05-api.md) durch: HTTP-Fehlercodes signalisieren gestörte Abläufe, nicht erwartbare Nutzereingabefehler.

---

## 2) AuthPolicy: Mehr-Faktor-Entscheidung

Die `next`-Tabelle aus Abschnitt 1 fragt nach jedem abgeschlossenen Tool die `AuthPolicy`, statt unbedingt abzuschließen. Sie ist die einzige Stelle, die entscheidet, ob genug Faktoren erbracht sind — und damit zugleich die Antwort auf den offenen Punkt „Policy-Gating zentralisieren" im [Umsetzungsstatus](README.md#umsetzungsstatus).

```kotlin
interface AuthPolicy {
    /** Reichen die in DIESER Session erbrachten Nachweise für das geforderte Niveau? */
    fun isSatisfied(evidence: AuthEvidence, requiredAcr: String): Boolean

    /** Welche Auth-Tools des Accounts könnten die verbleibende Lücke schließen? */
    fun candidateTools(evidence: AuthEvidence, requiredAcr: String, account: Account): List<String>

    /** Welche IDENT-Tools (Re-Identifikation, z. B. ident-fsc) könnten die Lücke ZUSÄTZLICH schließen? Siehe Abschnitt 3. */
    fun reIdentCandidates(evidence: AuthEvidence, requiredAcr: String): List<String>

    /** Kann der Account das Niveau KÜNFTIG aus eigener Kraft erreichen? (Registrierung) */
    fun canAccountReach(account: Account, requiredAcr: String): Boolean

    /** Welche Enroll-Tools würden diese Lücke schließen? */
    fun enrollmentCandidates(account: Account, requiredAcr: String): List<String>

    /** Welches Niveau ergibt sich aus den erbrachten Nachweisen? */
    fun resolveAcr(evidence: AuthEvidence): String
}

/** Was in dieser Session bereits nachgewiesen wurde — gelesen aus dem AuthContext. */
data class AuthEvidence(
    val amr: List<String>,                 // AuthContext.currentAmr
    val factorTypes: Set<FactorType>       // AuthContext.currentFactorTypes
)
```

Zwei Bedingungen, die zusammen erfüllt sein müssen:

1. **Niveau**: `resolveAcr(evidence) >= requiredAcr`. Die Abbildung von `amr`-Kombinationen auf `acr`-Werte ist fachlich/regulatorisch vorgegeben und wird hier bewusst nicht festgeschrieben.
2. **Faktorvielfalt**: Für MFA-Stufen mindestens zwei **verschiedene** Faktorarten. Gezählt wird die **Vereinigung** der `factorTypes` über alle bisher abgeschlossenen Tools — die Policy fragt nie, aus wie vielen Tools sie stammen.

Daraus folgt der Fall „MFA aus einem Tool" ohne Sonderbehandlung: Ein Passkey mit User Verification meldet `factorTypes = {possession, inherence}` und erfüllt die Bedingung im Alleingang; eine Smartcard mit PIN entsprechend `{possession, knowledge}`. Der Prozess endet dann nach einem einzigen Auth-Schritt. Umgekehrt genügen zwei Tools mit derselben Faktorart nicht — zweimal Besitz bleibt ein Faktor.

Wichtige Einschränkung dazu: Ein Tool darf nur die Faktoren melden, die es dem Server gegenüber tatsächlich **nachweisen** kann. Bei WebAuthn ist das `uv`-Flag kryptographisch im Authenticator-Signal enthalten und damit belastbar. Eine App-PIN, die nur lokal geprüft wird und dem Server gegenüber kein eigenes Beweisstück erzeugt, ist kein zweiter Faktor — sie schützt das Gerät, nicht die Anfrage. Für solche Verfahren gehört nur `{possession}` in den Descriptor.

Die Policy trifft damit nur die Entscheidungen, die ein einzelnes Modul nicht treffen kann: Was bedeutet eine *Kombination* von Nachweisen, und was fordert die Ressource? Welche Faktorarten und welches Niveau ein einzelnes Verfahren mitbringt, sagt das Modul selbst.

### Registrierung: Session-Nachweis ist nicht gleich Account-Fähigkeit

Beim Login lautet die Frage „reicht das *jetzt*?" (`isSatisfied`). Bei der Registrierung lautet sie „kommt der Nutzer damit *künftig wieder herein*?" (`canAccountReach`) — und das sind verschiedene Fragen, weil ein Identifikationsverfahren keine dauerhafte Auth-Methode ist:

- `ident-fsc` liefert `{possession}` und zählt in `AuthContext.currentFactorTypes` für diese Session. Beim nächsten Login ist die Gesundheitskarte aber nicht mehr im Spiel — sie landet in `account.identifications`, nicht in `account.authenticationMethods`.
- Wird bei der Registrierung nur ein Passwort eingerichtet, hätte die Session zwar `{possession, knowledge}`, der Account künftig aber nur `{knowledge}`. Ein späteres MFA-Login wäre unmöglich.

Fordert der Kanal also ein MFA-Niveau, muss die Registrierung so lange Enroll-Tools durchlaufen, bis `canAccountReach(account, requiredAcr)` erfüllt ist — im Regelfall zwei Methoden verschiedener Faktorart, etwa Gerätebindung (`possession`) plus Passwort (`knowledge`). Genau das bilden die REGISTRATION-Zeilen der Tabelle in Abschnitt 1 ab.

Daraus folgt eine Deckelungskette über drei Stufen, die in dieser Reihenfolge greift:

1. `account.identifications[].loa` begrenzt, was ein Account überhaupt je erreichen kann — mehr Vertrauen als bei der Identitätsfeststellung ist nicht herstellbar.
2. `authenticationMethods[].enrolledUnderAcr` begrenzt, was eine einzelne Methode liefern darf ([Abläufe](06-ablaeufe.md)).
3. `Completed.achievedAcr` meldet, was der konkrete Durchlauf erreicht hat.

Praktische Folge: Ein Kanal, der `loa3` verlangt, braucht bereits ein `loa3`-fähiges Identifikationsverfahren. Wurde nur mit `loa2` identifiziert, sind auch alle danach eingerichteten Methoden auf `loa2` gedeckelt — das Ziel bleibt unerreichbar, bis eine erneute Identifikation auf höherem Niveau erfolgt. Deshalb ist `requiredAcr` schon beim Anlegen des Kanals setzbar ([05-api.md](05-api.md), App-Fassade Beispiel 1): Nur so kann das Backend gleich das passende Ident-Verfahren anbieten, statt den Nutzer in eine Sackgasse laufen zu lassen.

Regeln für die Kandidatenermittlung:

- Kandidaten sind nur aktive Methoden des Accounts (`authenticationMethods[].active`), deren `enrolledUnderAcr` das geforderte Niveau überhaupt zulässt (Deckelung, [Abläufe](06-ablaeufe.md)) und deren `ToolDescriptor.maxAcr` die verbleibende Lücke schließen könnte.
- Bereits in dieser Session verwendete Methoden fallen heraus — sonst könnte derselbe Faktor zweimal zählen. Bei MFA-Anforderungen scheiden zusätzlich alle Methoden mit einem bereits erbrachten `factorType` aus.
- Ist die Kandidatenliste leer, obwohl die Policy nicht erfüllt ist, bricht der Prozess ab (`410`): Das geforderte Niveau ist mit den vorhandenen Methoden des Accounts nicht erreichbar. Für den Registrierungsfall bedeutet dieselbe Regel, dass ein weiteres Enrollment nötig ist.
- Bei genau einem Kandidaten überspringt der Orchestrator die Auswahlseite und zeigt direkt auf das Tool — dieselbe Skip-Regel wie im [API-Dokument](05-api.md).

Damit ist MFA keine Sonderbehandlung, sondern der Normalfall der bestehenden Schleife: Ein Prozess durchläuft so lange Tools, bis die Policy zufrieden ist.

---

## 3) MANAGE_METHODS: freiwillige Methodenverwaltung ohne Policy-Ziel

`ProcessPurpose.MANAGE_METHODS` mit `ManageMethodsProcessSession` (leere Marker-Subklasse wie `LoginProcessSession`, [Domänenmodell](02-domaenenmodell.md) Abschnitt 1/4) erlaubt einem bereits `AUTHENTICATED`-Kanal, freiwillig Auth-Mittel hinzuzufügen oder zu deaktivieren — losgelöst von der policy-getriebenen REGISTRATION/STEP_UP-Schleife aus Abschnitt 1/2.

Endpunkte (`ChannelController`/`ChannelService`, siehe [API](05-api.md)):

- `POST /orchestrator/api/v1/app/channels/{channelSessionId}/methods` (`startManageMethods`) — bietet `AuthPolicy.enrollmentCandidates()` an, dieselben Kandidaten und dieselben unveränderten Enroll-Tool-Handler wie REGISTRATION. Kanal muss `AUTHENTICATED` sein. Ist nichts mehr zu enrollen, ist das **kein Fehler**: Antwort `{"message": "Keine weiteren Mittel verfuegbar"}`, Kanal bleibt `AUTHENTICATED` ohne aktiven Prozess.
- `DELETE /orchestrator/api/v1/app/channels/{channelSessionId}/methods/{method}` (`deactivateMethod`) — deaktiviert ein aktives Mittel. Schutz gegen Selbstsperre: Der Zustand des Accounts *nach* der Entfernung wird berechnet und die Anfrage abgelehnt (`409`, `OrchestratorException.invalidState`), falls `AuthPolicy.canAccountReach()` danach unter das kanaleigene `requiredAcr` fiele.

Wesentlicher Verhaltensunterschied zu REGISTRATION: Der Abschluss von MANAGE_METHODS hängt **nicht** von `canAccountReach`/`isSatisfied` ab — im `Enrolled`-Zweig von `ToolOutcomeProcessor.resolveNext` wird `processSession is ManageMethodsProcessSession` **zuerst** geprüft (vor der Policy-Abfrage, siehe Tabelle in Abschnitt 1). Ein einziges erfolgreiches `Enrolled` beendet den Prozess sofort zurück zu `AUTHENTICATED`, unabhängig davon, ob dabei ein höheres Niveau erreicht wurde. Ein weiteres Mittel hinzuzufügen erfordert einen erneuten `POST .../methods`-Aufruf.

### loa2-Freigabe-Gate

Beide Endpunkte prüfen zuerst `ChannelService.requireManageMethodsAssurance(channel, account)`: Reicht der Nachweis **dieser Session** nicht bereits für `loa2` (`AuthPolicy.isSatisfied`), liefert die Methode direkt eine Step-up-Antwort (`startStepUp(channel, "loa2", allowReIdent=true)`) statt fortzufahren — fest auf `loa2`, unabhängig vom kanaleigenen `requiredAcr`.

Begründung: Dieselbe Anti-Selbsteskalations-Logik wie bei der `enrolledUnderAcr`-Deckelung (Abschnitt 1) — eine gekaperte `loa1`-Session darf nicht aus eigener Kraft Credentials hinzufügen oder entfernen können. Entfernen wird dabei als mindestens so sensibel wie Hinzufügen behandelt, deshalb gilt dieselbe Schranke für beide Endpunkte.

### Die Falle: Ein-Methoden-Konten ohne Weg zu loa2

Dieses Gate erzeugte zunächst einen echten Selbstsperr-Bug für den häufigen Fall: Ein Account mit genau **einer** aktiven Auth-Methode (z. B. nur SMS) hatte nach einem frischen geräte-gebundenen Login — der über `DeviceAccountLink` ([DPoP-Bindung](09-dpop.md) Abschnitt 3) direkt bei LOGIN landet und dabei nur `loa1` über dieses eine Mittel nachweist, ohne erneutes `ident-fsc` — **keinen** Weg, `loa2` je zu erreichen: Es gab keine zweite Methode, mit der sich die eine kombinieren ließe, um MANAGE_METHODS freizuschalten.

Die Lösung: `AuthPolicy.reIdentCandidates(evidence, requiredAcr)` (neue Schnittstellenmethode) bietet Re-Identifikation (`ident-fsc`) als alternativen Weg an. `ident-fsc` erreicht `loa2` bereits im Alleingang (eigenes `maxAcr="loa2"`, unabhängig davon, was der Account sonst enrollt hat) — eine erneute Bestätigung schließt die Lücke also direkt, ohne mit einer zweiten Methode kombiniert werden zu müssen.

Bewusst **nicht** in `candidateTools()` eingebaut, sondern eine eigene, separate Methode: Nur `ChannelService.startStepUp(channel, requiredAcr, allowReIdent=true)` fragt sie ab, und das ausschließlich für das MANAGE_METHODS-Gate — gewöhnliche LOGIN/STEP_UP-Kandidatenermittlung (`raiseRequiredAcr`, Abschnitt 1/2) ruft `reIdentCandidates` nie auf. Re-Identifikation wird dort also nie als generische Login-Abkürzung angeboten, nur gezielt dort, wo sie als Notausgang aus einer sonst unlösbaren Lage gebraucht wird.

### Plumbing, die Re-Identifikation mitten im Step-up erst nutzbar macht

Drei weitere, kleinere Anpassungen waren nötig, damit `ident-fsc` innerhalb eines Step-ups überhaupt sicher durchlaufen werden kann:

- `ToolOutcomeProcessor.handleIdentified` akzeptiert jetzt zusätzlich `StepUpProcessSession` (bisher nur `RegistrationProcessSession`). Während eines Step-ups **muss** die neu identifizierte `personId` zum bereits angemeldeten Account passen — bei Abweichung `409 CONFLICT` (`OrchestratorException.invalidState`, „Identifizierte Person passt nicht zum angemeldeten Konto"). Das ist eine echte Sicherheitsschranke, kein Implementierungsdetail: Ohne sie könnte eine `loa1`-Session eine fremde KVNR/FSC einschleusen und damit einen anderen Account kapern.
- `ToolOutcomeProcessor.resolveNext`, Zweig `Identified`: Ist `processSession is StepUpProcessSession` und reicht die Identifikation allein bereits für das geforderte Niveau, schließt der Prozess sofort ab (`finishAsAuthenticated`) — anders als bei REGISTRATION, wo Identifikation allein nie genügt (Abschnitt 2, „Session-Nachweis ist nicht gleich Account-Fähigkeit").
- `ToolControllerSupport.EXPECTED_CATEGORIES_BY_CONTEXT["auth"]` akzeptiert jetzt sowohl `ToolCategory.AUTH` als auch `ToolCategory.IDENT` (vorher nur `AUTH`) — nötig, weil `ident-fsc` (Kategorie IDENT) während dieses Step-ups legitim als Kandidat im Auswahlkontext `"auth"` auftauchen kann.

---

## 4) Lookup-basierter Login ("Login ohne DPoP")

Löst das strukturelle Problem, dass ein NICHT über `DeviceAccountLink` verlinktes Gerät bisher nur REGISTRATION angeboten bekam — es gab keinen Weg, sich ohne vorherige Geräte-Bindung in einen bereits bestehenden Account einzuloggen (klassischer Web-Login: nur Identifier + Credential, kein gepaartes Gerät nötig).

### `intent`-Parameter am Kanaleinstieg

`POST /channels` bekommt ein optionales Feld `intent: "auto" | "login" | "register"` ([API](05-api.md)). `ChannelService.initializeChannel` unterdrückt den `DeviceAccountLink`-Lookup für `intent != "auto"` komplett (`linkedAccountId = null`), sodass die Kanal-eigene `accountId` in beiden Fällen `null` bleibt:

- `intent="register"` fällt dadurch automatisch in den bestehenden `resumeChannel`-Zweig `channel.accountId == null -> startRegistration` — kein neuer Code nötig, nur die Link-Unterdrückung.
- `intent="login"` ruft stattdessen explizit die neue `ChannelService.startLookupLogin(channel)` auf, statt `resumeChannel`.

### `startLookupLogin`: Kandidaten ohne bekannten Account

`startLookupLogin` bietet den fixen Werkzeugsatz `["auth-sms-lookup", "auth-password-lookup"]` direkt an (`CandidateOffering.resolve`) — **nicht** über `AuthPolicy.candidateTools`, das einen bereits aufgelösten `account` braucht, den es an dieser Stelle noch gar nicht geben kann (die E-Mail wurde ja noch nicht eingegeben). Der Prozess bleibt eine gewöhnliche `LoginProcessSession`; kein neuer `ProcessPurpose` nötig.

### `ToolDescriptor.deviceBound`: Werkzeugkatalog-Kollision vermeiden

`auth-sms-lookup`/`auth-password-lookup` melden bewusst `method="sms"`/`"password"` — dieselben Werte wie ihre geräte-gebundenen Geschwister `auth-sms`/`auth-password` (es ist fachlich dasselbe Credential, nur ein anderer Weg, es zu präsentieren). Das würde `AuthPolicy.candidateTools()`s bisherigen `firstOrNull { category==AUTH && method==m.method }`-Lookup mehrdeutig machen, sobald ein Account einmal per Lookup eingeloggt war: Für eine ganz normale, bereits Account-gebundene LOGIN/STEP_UP-Fortsetzung dürfte NIE der `-lookup`-Zwilling zurückkommen (der erwartet eine erneute E-Mail-Eingabe, obwohl der Account längst bekannt ist). Fix: `ToolDescriptor.deviceBound: Boolean` (Default `true`), nur die beiden `-lookup`-Tools setzen `false`; `candidateTools()`s Lookup filtert zusätzlich auf `it.deviceBound`.

### `Completed.Authenticated.accountId` und die Account-Auflösung selbst

`ToolOutcome.Completed.Authenticated` bekommt ein optionales `accountId`-Feld, das NUR die `-lookup`-Tools setzen (gewöhnliche `auth-*`-Tools kennen den Account schon vorher über den Kanal, siehe Abschnitt 1). Die Auflösung E-Mail -> Account passiert bewusst im jeweiligen Controller (`AuthSmsLookupToolController`/`AuthPasswordLookupToolController`), nicht im Handler: `auth_sms`/`auth_password` dürfen laut Modulith-Grenzen nur von `tool_spi` abhängen ([Projektrahmen](08-projektrahmen.md) A11), nicht von `account`. Derselbe Modulgrenzen-Grund, aus dem schon die geräte-gebundenen `auth-*`-Tools ihre `EnrollmentRef` vom Controller statt selbst auflösen lassen.

`ToolOutcomeProcessor.handleAuthenticated` verwendet `outcome.accountId`, falls gesetzt, statt (wie bisher) zwingend `processSession.accountId ?: channelSession.accountId` vorauszusetzen, bindet ihn an Kanal und Prozess und ruft `sessionManagementService.linkDeviceToAccount(...)` — derselbe Hook wie bei `handleEnrolled` (Abschnitt 1): Ein erfolgreicher Lookup-Login ist ein genauso starker Credential-Nachweis wie eine frische Enrollment und rechtfertigt dieselbe Geräte-Verlinkung. Ein Kanal, der danach mit `intent="auto"` angelegt wird, erkennt das Gerät entsprechend wieder und bietet den gewöhnlichen geräte-gebundenen LOGIN an.

### Enumeration-Schutz

Eine unbekannte E-Mail liefert bei beiden `-lookup`-Tools exakt dieselbe Antwortform wie ein korrekt aufgelöster Account mit falschem Credential (`Failed`, `200`, Retry angeboten) — nie eine eigene Fehlerform. `auth-sms-lookup` „verschickt" dabei sogar konsequent eine (nutzlose) TAN-Erwartung, nur ohne `demoTan` in der Antwort, damit Timing/Form zwischen unbekannter E-Mail und bekannter E-Mail möglichst gleich bleiben. Bewusst nicht weiter gehärtet (kein künstliches Timing-Padding) — für eine Demo ausreichend, in einem Produktivsystem wäre das der nächste Schritt.
