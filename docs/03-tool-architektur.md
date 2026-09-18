# Tool-Architektur

Ein *Tool* ist ein konkretes Verfahren zur Identifikation, zum Enrollment oder zur Authentifizierung
(`ident-fsc`, `enroll-sms`, `auth-sms`). Dieses Dokument beschreibt, wie Tools sich selbst
beschreiben und was sie über die Modulgrenze melden.

Was der Orchestrator mit diesen Meldungen macht, steht in [04-orchestrierung.md](04-orchestrierung.md).

---

## 1) Tool-Katalog

`ToolSession` ist die dritte und kurzlebigste Ebene (`ChannelSession` -> `AuthJourney` -> `ToolSession`) und steht für genau einen Durchlauf eines Tools; sie trägt nur technische Lifecycle-Metadaten. `toolId` (z. B. `ident-fsc`, `enroll-sms`, `auth-sms`) identifiziert Kind und Methode in einem flachen Bezeichner — kein persistiertes Feld, sondern ergibt sich aus der Route und wählt darüber Handler und Moduldaten-Klasse.

Der Tool-Katalog ist **keine zentral gepflegte Tabelle**, sondern die Aggregation der Selbstauskünfte aller Module (`ToolDescriptor`, Abschnitt 2):

| toolId | role | method | factorTypes | maxAcr | allowsMultipleInstances |
|---|---|---|---|---|---|
| `ident-fsc` | `IDENTIFICATION` | `fsc` | `{possession}` | `loa2` | — |
| `ident-eid` | `IDENTIFICATION` | `eid` | `{possession,knowledge}` | `loa3` | — |
| `confirm-email` | `ATTESTATION` | `email` | `{}` | `loa1` | — |
| `enroll-sms` / `auth-sms` | `ENROLLMENT` / `IDENTIFIED_AUTH` | `sms` | `{possession}` | `loa1` | `false` |
| `enroll-password` / `auth-password` | `ENROLLMENT` / `IDENTIFIED_AUTH` | `password` | `{knowledge}` | `loa1` | `false` |
| `enroll-email` / `auth-email` | `ENROLLMENT` / `IDENTIFIED_AUTH` | `email` | `{knowledge}` | `loa1` | `false` |
| `auth-sms-lookup` / `auth-password-lookup` / `auth-email-lookup` | `LOOKUP_AUTH` | `sms`/`password`/`email` | wie Zwilling | `loa1` | `false` |
| `enroll-device` / `auth-device` | `ENROLLMENT` / `IDENTIFIED_AUTH` | `device` | `{possession,knowledge,inherence}` | `loa2` | `true` |
| `enroll-qr` / `auth-qr` / `auth-qr-lookup` | `ENROLLMENT` / `IDENTIFIED_AUTH` / `LOOKUP_AUTH` | `qr` | `{}` / `{possession,knowledge}` / `{possession,knowledge}` | `loa1` / `loa2` / `loa2` | `false` |
| `confirm-qr-login` | `PEER_APPROVAL` | `qr` | `{}` | `loa2` | — |

Entscheidungen dahinter:

- Jedes Modul liefert Kategorie/Methode/Faktorart/Niveau selbst. Ein neues Modul bringt seine Beschreibung mit; niemand muss eine zentrale Liste nachpflegen.
- `method` wird **nicht** aus `toolId` geparst: `enroll-sms`/`auth-sms` melden dieselbe `method`, worüber ein Auth-Tool die passende Zeile in `account.auth_method` findet.
- `factorTypes` ist die Grundlage für MFA-Prüfungen ([Orchestrierung](04-orchestrierung.md)) — eine **Menge**, weil ein einzelnes Verfahren mehrere Faktoren zugleich erbringen kann. `enroll-device`/`auth-device` ist genau dieser Fall: ein gerätegebundenes, nicht-extrahierbares Schlüsselpaar, dessen Nutzung durch einen (im Demo gemockten) System-PIN/Biometrie-Prompt gattet ist, meldet `loa2` und zwei Faktorarten aus einem Durchlauf.
- `requires` (aktuell nur `enroll-password` mit `ClaimRequirement(EMAIL, PROVEN)`) wird an zwei Stellen unabhängig geprüft: bei der Kandidatenermittlung (`AuthPolicy.enrollmentCandidates`) und nochmals bei der Aktivierung selbst (`ToolControllerSupport.validatePreconditions`), sonst ließe sich die Kandidatenliste per Direktaufruf umgehen. Deshalb ist `enroll-email` die aktuell einzige zusätzliche Required Action bei der Registrierung ([Orchestrierung](04-orchestrierung.md) #2).
- **Verfügbarkeit** hat zwei unabhängige Achsen, beide als `toolId`-Mengen: Der Client erklärt bei der Kanal-Erzeugung (`POST /channels`, Pflichtfeld `availableTools`), welche Tools er rendern kann — fix für die Lebensdauer des Kanals. Das Backend kann zusätzlich jedes Tool global und zur Laufzeit sperren (`ToolAvailabilityService`, `PUT /admin/tools/{toolId}/availability`, ohne Neustart wirksam). Beide Achsen werden live geschnitten (`JourneyContext.availableTools`): `CandidateTools` filtert bei jeder Zustandsübergangs-Berechnung, `JourneyState.activatable()` zusätzlich bei *jedem* Request neu, `ToolControllerSupport.beginActivation` prüft defensiv ein drittes Mal. Bleibt nichts Verfügbares übrig, greift derselbe `exhausted`/Cancel-Fallback wie bei einer vollständig abgelehnten Kandidatenliste.
- `role=ATTESTATION` (`confirm-email`) markiert einen Nachweis, der weder Identifizierung noch
  Authentifizierung noch Enrollment ist: Die bestätigte Adresse wird Account-Attribut, nicht
  Credential — kein `enrollmentRef`, kein `amr`, kein Niveau-Zuwachs. Ausführlich in Abschnitt 2
  ("`ATTEST`: ein Attribut bezeugen ist weder Identifizierung noch Anmeldung").
- `role=LOOKUP_AUTH` markiert die `-lookup`-Zwillinge: Sie melden dieselbe `method` wie ihr `IDENTIFIED_AUTH`-Geschwister (dasselbe Credential, anderer Präsentationsweg) und lösen den Account selbst über eine eingegebene E-Mail auf, statt ihn über den Kanal zu kennen ([Orchestrierung](04-orchestrierung.md) Abschnitt 4). Ohne diese Unterscheidung könnte die Kandidatenermittlung mehrdeutig auf den `-lookup`-Zwilling statt das Original treffen — `category=AUTH` allein reicht dafür nicht, `role` schon.
- `allowsMultipleInstances=true` (bislang nur `device`): mehrere aktive Instanzen derselben Methode dürfen gleichzeitig existieren, eine pro physischem Gerät, statt der sonst üblichen "eine aktive Instanz, neu enrollen ersetzt die alte"-Regel (`AccountService.addAuthenticationMethod`). Jede Instanz bekommt eine stabile `id` und einen vom Nutzer vergebenen `label`, damit beim Deaktivieren klar ist, welches Gerät gemeint ist. `AuthPolicy.candidateTools` filtert `auth-device` zusätzlich auf die Instanz, deren `deviceBindingKeyRef` zum anfragenden physischen Gerät passt. `ToolDescriptor.matchesCurrentOwner` bündelt diesen Schlüsselvergleich mit einer zweiten Bedingung: das Gerät muss laut `DeviceAccountLink` **aktuell noch** an genau dieses Konto gebunden sein (`linkedAccountId == accountId`) — nach einem Rebind ([04-orchestrierung.md](04-orchestrierung.md) "REGISTER") verliert das alte Konto sein Geräte-Credential für diesen Schlüssel sofort.

- `enroll-qr`/`auth-qr`/`auth-qr-lookup` (Modul `auth_qr`, QR-Login des Web-Kanals, bestätigt über den App-Kanal — [Orchestrierung](04-orchestrierung.md) `CONFIRM_PEER_LOGIN`) folgen demselben Enroll/Auth/Lookup-Dreiklang wie `sms`/`password`/`email`, mit einer Besonderheit: `enroll-qr` ist ein reiner Opt-in-Marker ohne Geheimnis (`factorTypes = {}`). `auth-qr-lookup` bleibt trotzdem immer anbietbar — die Opt-in-Prüfung verschiebt sich dann auf `confirm-qr-login`, das ohne aktives `enroll-qr` mit `Failed("QR-Login ist für dieses Konto nicht aktiviert")` abbricht.
- `auth-qr`/`auth-qr-lookup` deklarieren `factorTypes = {possession, knowledge}`, nicht nur `possession`: Das bestätigende Handy darf laut `ConfirmPeerLoginStrategy.gate()` ([Orchestrierung](04-orchestrierung.md) `CONFIRM_PEER_LOGIN`) selbst erst bestätigen, nachdem seine EIGENE Session frisch loa2 nachgewiesen hat. Zwei Faktor-Typen machen dieses Tool damit selbst-genügsame MFA (wie bei `ident-eid`), konsistent zum `maxAcr=loa2`.
- `confirm-qr-login` trägt die Rolle `MethodRole.PEER_APPROVAL` (Kategorie `SIDE_ACTION`, Abschnitt 2) — keine bestehende Rolle passt auf „ich bestätige den Login eines *anderen* Kanals".

Zentral bleibt nur, was ein Modul nicht wissen *kann*: welches Niveau sich aus einer **Kombination** von Nachweisen ergibt und welches Niveau eine Ressource fordert — Sache der `AuthPolicy` ([Orchestrierung](04-orchestrierung.md)).

---

## 2) `ToolDescriptor` und `ToolOutcome`

Jedes Tool bringt eine eigene Descriptor-Bean mit (`object EnrollSmsDescriptor : ToolDescriptor`, gebündelt in `Descriptors.kt` je Modul) statt dass der Handler das Interface selbst implementiert — reine Selbstbeschreibung ohne Dependencies, getrennt von der Geschäftslogik, die dafür in `internal` liegen kann. `maxAcr`/`factorTypes` liest der Handler über eine injizierte Referenz auf seinen eigenen Descriptor. Der Orchestrator sammelt die Descriptors beim Start ein und aggregiert daraus den Katalog aus Abschnitt 1:

| Feld | Bedeutung |
|---|---|
| `toolId` | z. B. `"auth-sms"` — frei vergeben, nie aus `role`/`method` abgeleitet (öffentlicher API-Vertrag) |
| `method` | z. B. `"sms"` — verbindet `enroll-sms`/`auth-sms`/`auth-sms-lookup` |
| `role` | `IDENTIFICATION` \| `ATTESTATION` \| `ENROLLMENT` \| `IDENTIFIED_AUTH` \| `LOOKUP_AUTH` \| `PEER_APPROVAL`; `role.category` (`IDENT`/`ATTEST`/`ENROLL`/`AUTH`/`SIDE_ACTION`) wird direkt gelesen, nicht auf dem Descriptor dupliziert |
| `factorTypes`, `maxAcr` | statische Obergrenzen dieses Tools |
| `requires`, `allowsMultipleInstances` | leere Menge bzw. `false` per Default |

`(method, role)` ist der eindeutige Schlüssel für "das konkrete Verfahren dieser Art für dieses Credential" — `(method, role.category)` allein ist es **nicht**: `IDENTIFIED_AUTH` und `LOOKUP_AUTH` teilen sich `category=AUTH`. `ToolHandlerRegistry` prüft beim Einsammeln der Descriptors, dass kein `(method, role)`-Paar doppelt vorkommt — ein Duplikat bräche sonst still auf einen beliebigen Treffer zusammen, statt laut beim Start zu scheitern.

`tool_spi` kennt **keine** konkreten Methoden — jedes Modul deklariert seine eigene Konstante (z. B. `auth_sms/Descriptors.kt`: `internal const val SMS_METHOD = "sms"`). Das hält den Katalog bei seinem Grundprinzip (Abschnitt 1: keine zentral gepflegte Liste). `toolId` bleibt bewusst *nicht* aus `(method, role)` abgeleitet: es ist der öffentliche API-Vertrag (URL-Pfade, Frontend-Routing) und darf nicht von einer internen Umbenennung mitgerissen werden, auch wenn die aktuellen Werte dem Muster `{role-präfix}-{method}[-lookup]` folgen.

`maxAcr`/`factorTypes` sind statisch und dienen der Vorauswahl (kann dieses Tool eine Lücke überhaupt schließen?); was ein konkreter Durchlauf tatsächlich erbracht hat, meldet `Completed` — nie mehr, als der Descriptor zulässt.

Über die Modulgrenze geht ausschließlich `ToolOutcome` — läuft noch, abgeschlossen, oder
fehlgeschlagen:

| Variante | Bedeutung |
|---|---|
| `InProgress(nextStep, data)` | läuft weiter; `data` ist client-gerichtet und wird unverändert als `stepData` durchgereicht |
| `Failed(reason)` | Versuch fehlgeschlagen; Retry-Regel siehe [Orchestrierung](04-orchestrierung.md) |
| `Completed.Identified(claims, ...)` | Person identifiziert; genau ein gültiger `PERSON_ID`-Claim |
| `Completed.Attested(claims, ...)` | Attribut bezeugt; kein `enrollmentRef`, `amr` fest leer (Abschnitt „ATTEST" unten) |
| `Completed.Enrolled(enrollmentRef, ...)` | Methode eingerichtet |
| `Completed.Authenticated(accountId?, ...)` | Nachweis erbracht — `accountId` nur bei `-lookup`-Tools gesetzt |
| `Completed.Approved(...)` | Ein `PEER_APPROVAL`-Tool (`confirm-qr-login`) hat eine fremde Anfrage bestätigt |

Jede `Completed`-Variante trägt zusätzlich `amr` (nachgewiesene Methoden, für
`AuthContext.currentAmr`), `achievedAcr` und `factorTypes` (Teilmenge der `ToolDescriptor.factorTypes`).
Die Variante *ist* die Kategorie und legt fest, was der Orchestrator tut.

### `ATTEST`: ein Attribut bezeugen ist weder Identifizierung noch Anmeldung

`confirm-email` weist nach, dass jemand eine Adresse **kontrolliert** — dort kommt ein Code an. Das
ist weder „wer bist du" (`IDENT`) noch „weise ein Mittel nach" (`AUTH`) noch „richte ein Mittel ein"
(`ENROLL`). Deshalb eine eigene Kategorie mit `MethodRole.ATTESTATION` und der Ergebnisform
`ToolOutcome.Completed.Attested`: Claims ja, `enrollmentRef` nein, `amr` ausdrücklich leer — eine
bestätigte Adresse darf das Niveau des Kanals nicht anheben.

Unter `IDENT` einzuordnen wäre falsch: `CandidateTools.forIdentification` und
`DefaultAuthPolicy.reIdentCandidates` böten das Tool als Identifizierungsverfahren an, und
`evidenceAxis` legte seine Evidenz auf die IDENTITY-Achse — damit höbe eine Adressbestätigung das
IAL, den ersten der drei Deckel aus ADR-5.

**Wann welche Kategorie**, für das nächste Attribut: Die Antwort steht schon in zwei deklarierten
Angaben — `ClaimSource` (wer bürgt) und `AttributeType.authority` (wem der aktuelle Wert gehört).
Bürgt das Register (`EXT_STAMMDATEN`), ist es `IDENT`; bürgt der Austausch selbst
(`ClaimSource.of(toolId)`) und gehört der Wert dem Konto (`LOCAL_ANCHOR`), ist es `ATTEST`; gehört
er dem Methodenmodul (`METHOD_MODULE`), ist es `ENROLL`. Über die KVNR lässt sich keine Kontrolle
nachweisen, nur die Zugehörigkeit zur Person — also `IDENT`, kein `attest-kvnr`.

`ToolCategory.SIDE_ACTION` benennt die geteilte Eigenschaft von `PEER_APPROVAL`-Tools: sie tragen
nichts zur ACR/AMR-Bilanz des *eigenen* Kanals bei und sind nie Kandidat einer Lücken-Vorauswahl,
nur explizit per `intent` aktiviert ([Orchestrierung](04-orchestrierung.md) `CONFIRM_PEER_LOGIN`).
Bewusst **nicht** `MISC`/`OTHER`: das würde die Exhaustivität unterlaufen, die `ToolCategory` als
versiegeltes `enum` herstellt. `AuthPolicy.candidateTools`/`enrollmentCandidates` haben einen
eigenen `SIDE_ACTION`-Zweig, der nichts anbietet. Ablehnen einer Peer-Anfrage ist **kein** eigener
`ToolOutcome` — `Failed(reason = "Vom Nutzer abgelehnt")` reicht.

- `InProgress.data` ist **client-gerichtet** (z. B. `missingFields`); `Completed`/`Failed` sind **orchestrator-gerichtet** und werden nie direkt an den Client durchgereicht (siehe [Orchestrierung](04-orchestrierung.md)).
- `amr`/`achievedAcr` liefert jedes Tool selbst, weil dasselbe Verfahren je nach Ausführung unterschiedliche Niveaus erreichen kann.
- `Completed.Authenticated.accountId` setzen nur die `-lookup`-Tools, die den Account selbst auflösen — gewöhnliche `auth-*`-Tools kennen ihn schon über den Kanal.
- Ein Controller pro Tool ruft seinen Handler direkt auf, typisiert statt über eine generische `Map<String, Any?>` — kein `toolId`-basierter Laufzeit-Dispatch ([Projektrahmen](08-projektrahmen.md) A11: „Lesbarkeit hat Vorrang vor maximal generischem API-Wiring"). Dieser Controller lebt im selben Modul wie sein Handler (Abschnitt 4). Referenzen wie `EnrollmentRef` werden am Aufrufort im Controller aufgelöst und geprüft — der Handler bekommt nie einen nullable Parameter.
- **Die bestätigte E-Mail ist ein Account-Attribut, kein modul-eigenes Credential.** `auth_email` liefert einen `EMAIL`-Claim in `Completed.Enrolled`; die Journey übernimmt ihn über `AccountService.recordClaims`, ohne dass `auth_email` eine Abhängigkeit auf `account` hätte. `auth-email-lookup`, `auth-sms-lookup` und `auth-password-lookup` verwenden die `resolveAccountByEmail`-Extension auf `AccountDirectory`; sie liefert nur eine Account-ID, kein Profil.

---

## 3) Modulinterne Flow-Architektur (optional)

Ein Methodenmodul darf sich intern frei organisieren — nur `ToolOutcome` verlässt die Modulgrenze, nie ein interner Zustand. Ein mögliches, nicht vorgeschriebenes Muster: ein reiner `Flow`, der nur seinen eigenen `State` kennt und aus `(State, Input)` eine `Decision` mit nächstem Zustand, Effekten (z. B. „TAN senden") und einem neutralen `FlowOutcome` (`InProgress`/`Completed`/`Failed`) ableitet; der Handler übersetzt `FlowOutcome` in `ToolOutcome`.

---

## 4) Wo der Controller lebt: `tool_api` als Modulgrenze

Der `@RestController` eines Tools lebt **im selben Modul wie sein Handler** (z. B. `id_fsc.api.v1.IdentFscToolController`, `auth_sms.api.v1.AuthSmsToolController`), nicht im `orchestrator`. Der Orchestrator kennt kein Methodenmodul namentlich — `orchestrator/ModuleMetadata.kt` deklariert `allowedDependencies = ["tool_spi", "tool_api", "account", "ext_stammdaten"]`, ohne `id_fsc`, `auth_sms`, `auth_password`, `auth_email` oder `auth_device`.

Ermöglicht wird das durch das gemeinsame SPI-Modul `tool_api` (`allowedDependencies = ["tool_spi"]`), das beide Seiten kennen dürfen:

- **`ToolEndpoint`** — Session-/Journey-Mechanik jedes Tool-Controllers (Aktivierung starten, Kontext laden, Ergebnis anwenden, Journey abbrechen). Implementiert von `ToolControllerSupport` im `orchestrator`, per Konstruktor injiziert.
- **`AccountDirectory`** / **`PersonDirectory`** / **`DeviceProofs`** — schmale Lese-/Prüf-Ports auf Konto-, Personen- und Geräte-Nachweis-Daten (z. B. `auth-sms-lookup` zum Auflösen eines Kontos über E-Mail). Implementiert von `AccountService` (`account`), `ExtStammdatenService` (`ext_stammdaten`) bzw. `DeviceProofValidator` (`orchestrator`) — jeweils direkt am Domänenservice, ohne separate Adapterklasse.
- **`EnrollmentCleanup`** — die Gegenrichtung: ein Schreib-Port AUS einem Methodenmodul heraus, für den Fall der Account-Löschung ([API](05-api.md), "Account löschen"). Jedes Modul mit eigener Langzeit-Credential-Tabelle (`auth_sms.enrollment`, `auth_password.enrollment`, `auth_device.enrollment`, `auth_qr.enrollment` — Tabellenname = `EnrollmentRef.type`) bringt eine `@Component`-Implementierung mit, die per `enrollmentType` angesprochen wird; `auth_email` braucht keine, weil die bestätigte E-Mail dem Account-Modul gehört (Abschnitt 2). `AccountDeletionService` (`orchestrator`) sammelt `List<EnrollmentCleanup>` wie `ToolHandlerRegistry` die `ToolDescriptor`-Beans ein — ohne dass `orchestrator` oder `account` ein Methodenmodul beim Namen kennen müsste.
- **Envelope-DTOs** (`ChannelResponse`, `ChannelBlock`, `ActiveMethodView`, `Next`, `DemoInfo`) — die gemeinsame Antwortform, die jeder Tool-Controller zurückgibt.
- **`ToolSwitchController`** — der einzige generische, toolId-lose Controller (Tool wechseln/abbrechen ohne tool-spezifische Logik); lebt deshalb direkt in `tool_api` statt in einem einzelnen Methodenmodul.

Beide Seiten zeigen auf dasselbe Interface, nie direkt aufeinander: ein Methodenmodul ruft `ToolEndpoint`-Methoden auf, ohne `ToolControllerSupport` oder den `orchestrator` zu kennen; der `orchestrator` liest nie einen Handler eines Methodenmoduls. Die HTTP-Pfade (`/orchestrator/api/v1/tools/...`) sind unverändert — Spring routet nach `@RequestMapping`, nicht nach Kotlin-Package. Details zur Modulliste und den Abhängigkeitsrichtungen: [Projektrahmen](08-projektrahmen.md) Abschnitt 3.
