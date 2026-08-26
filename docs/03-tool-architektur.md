# Tool-Architektur

Ein *Tool* ist ein konkretes Verfahren zur Identifikation, zum Enrollment oder zur Authentifizierung
(`ident-fsc`, `enroll-sms`, `auth-sms`). Dieses Dokument beschreibt, wie Tools sich selbst
beschreiben und was sie über die Modulgrenze hinweg melden.

Was der Orchestrator mit diesen Meldungen macht, steht in [04-orchestrierung.md](04-orchestrierung.md).

---

## 1) Tool-Katalog

`ToolSession` ist die dritte und kurzlebigste Ebene (`ChannelSession` -> `AuthJourney` -> `ToolSession`) und steht für genau einen Durchlauf eines Tools; sie trägt nur technische Lifecycle-Metadaten, keine Kind-Subtypen. `toolId` (z. B. `ident-fsc`, `enroll-sms`, `auth-sms`) identifiziert Kind und Methode zusammen in einem flachen Bezeichner — kein persistiertes Feld, sondern ergibt sich aus der Route und wählt darüber Handler und Moduldaten-Klasse.

Der Tool-Katalog ist **keine zentral gepflegte Tabelle**, sondern die Aggregation der Selbstauskünfte aller Module (`ToolDescriptor`, Abschnitt 2):

| toolId | role | method | factorTypes | maxAcr | allowsMultipleInstances |
|---|---|---|---|---|---|
| `ident-fsc` | `IDENTIFICATION` | `fsc` | `{possession}` | `loa2` | — |
| `enroll-sms` / `auth-sms` | `ENROLLMENT` / `DEVICE_AUTH` | `sms` | `{possession}` | `loa1` | `false` |
| `enroll-password` / `auth-password` | `ENROLLMENT` / `DEVICE_AUTH` | `password` | `{knowledge}` | `loa1` | `false` |
| `enroll-email` / `auth-email` | `ENROLLMENT` / `DEVICE_AUTH` | `email` | `{possession}` | `loa1` | `false` |
| `auth-sms-lookup` / `auth-password-lookup` / `auth-email-lookup` | `LOOKUP_AUTH` | `sms`/`password`/`email` | wie Zwilling | `loa1` | `false` |
| `enroll-device` / `auth-device` | `ENROLLMENT` / `DEVICE_AUTH` | `device` | `{possession,knowledge,inherence}` | `loa2` | `true` |

Entscheidungen dahinter:

- Jedes Modul liefert Kategorie/Methode/Faktorart/Niveau selbst — es weiß am besten, was sein Verfahren maximal trägt. Ein neues Modul bringt seine Beschreibung mit; niemand muss eine zentrale Liste nachpflegen.
- `method` wird **nicht** aus `toolId` geparst (der Name ist ein Bezeichner, kein strukturiertes Datum) und trägt echte Information: `enroll-sms`/`auth-sms` melden dieselbe `method`, worüber ein Auth-Tool die passende Zeile in `account.authenticationMethods` findet.
- `factorTypes` ist die Grundlage für MFA-Prüfungen ([Orchestrierung](04-orchestrierung.md)) — eine **Menge**, weil ein einzelnes Verfahren mehrere Faktoren zugleich erbringen kann. `enroll-device`/`auth-device` ist genau der Fall, der hier lange nur als Hypothese stand ("ein hypothetischer Passkey mit User Verification wäre Besitz *und* Inhärenz und erfüllte MFA im Alleingang"): ein gerätegebundenes, nicht-extrahierbares Schlüsselpaar, dessen Nutzung durch einen (im Demo gemockten) System-PIN/Biometrie-Prompt gattet ist, meldet direkt `loa2` und zwei Faktorarten aus einem einzigen Durchlauf — Besitz des Schlüssels plus Wissen (PIN) oder Inhärenz (Biometrie), je nachdem, was der Prompt pro Versuch bestätigt (nicht fest beim Enrollment verdrahtet — reale Geräte legen sich darauf auch nicht fest).
- `requiresConfirmedEmail` (bislang nur `enroll-password`) wird an zwei Stellen unabhängig geprüft: bei der Kandidatenermittlung (`AuthPolicy.enrollmentCandidates`) und nochmals bei der Aktivierung selbst (`ToolControllerSupport.validatePreconditions`) — letzteres, weil die Aktivierungsprüfung sonst nur die Tool-*Kategorie* gegen den Auswahlkontext prüft, nicht den konkreten Kandidaten, und einen direkten Aufruf unter Umgehung der Kandidatenliste sonst durchließe. Genau deshalb ist `enroll-email` die aktuell einzige konkrete zusätzliche Required Action bei der Registrierung ([Orchestrierung](04-orchestrierung.md) #2) — ohne bestätigte E-Mail bliebe `enroll-password` sonst dauerhaft unerreichbar, selbst wenn ein anderer Faktor das ACR-Ziel längst erfüllt.
- `role=LOOKUP_AUTH` markiert die `-lookup`-Zwillinge: Sie melden bewusst dieselbe `method` wie ihr `DEVICE_AUTH`-Geschwister (dasselbe Credential, nur ein anderer Weg, es zu präsentieren) und lösen den Account selbst über eine eingegebene E-Mail auf, statt ihn schon über den Kanal zu kennen ([Orchestrierung](04-orchestrierung.md) Abschnitt 4). Ohne diese Unterscheidung könnte die normale Kandidatenermittlung für eine ganz gewöhnliche, bereits Account-gebundene Session mehrdeutig auf den `-lookup`-Zwilling statt das Original treffen — `category=AUTH` allein reicht dafür nicht, `role` schon.
- `allowsMultipleInstances=true` (bislang nur `device`): mehrere aktive Instanzen derselben Methode dürfen gleichzeitig existieren, eine pro physischem Gerät, statt der sonst üblichen "eine aktive Instanz, neu enrollen ersetzt die alte"-Regel (`AccountService.addAuthenticationMethod`). Jede Instanz bekommt eine stabile `id` und einen vom Nutzer vergebenen `label` — ohne `id` ließe sich beim Deaktivieren nicht sagen, welches von mehreren gleichnamigen Geräten gemeint ist. `AuthPolicy.candidateTools` filtert `auth-device` zusätzlich auf die Instanz, deren `deviceBindingKeyRef` (das DPoP-bewiesene Geräte-Fingerprint des aufrufenden Kanals) zum anfragenden physischen Gerät passt — ein nicht-extrahierbarer Schlüssel kann strukturell nirgends sonst liegen, ihn anderswo anzubieten würde garantiert scheitern. Auf beiden Geschwistern (`enroll-device` UND `auth-device`) unabhängig deklariert, genau wie `maxAcr`/`factorTypes` — keine erzwungene Gleichheit zwischen Tool-Varianten (ein künftiger `LOOKUP_AUTH`-Zwilling dürfte hier legitim abweichen). Gelesen wird das Flag deshalb nie über eine mehrdeutige, nur-nach-Methodenname suchende Zuordnung, sondern stets vom bereits eindeutig per `(method, role)` aufgelösten Descriptor des konkret betrachteten Tools.

Zentral bleibt nur, was ein einzelnes Modul nicht wissen *kann*: welches Niveau sich aus einer **Kombination** von Nachweisen ergibt, und welches Niveau eine Ressource fordert — Sache der `AuthPolicy` ([Orchestrierung](04-orchestrierung.md)).

---

## 2) `ToolDescriptor` und `ToolOutcome`

Jedes Tool bringt eine eigene, kleine Descriptor-Bean mit (`object EnrollSmsDescriptor : ToolDescriptor`, gebündelt mit ihren Geschwistern in `Descriptors.kt` je Modul) statt dass der Handler das Interface selbst implementiert — reine Selbstbeschreibung ohne Dependencies, getrennt von der Geschäftslogik, die dafür ungehindert in `internal` liegen kann (DPoP-demo-vun). Kein Aufrufer ruft je eine Handler-Methode über die `ToolDescriptor`-Referenz auf; `maxAcr`/`factorTypes` liest der Handler intern über eine injizierte Referenz auf seinen eigenen Descriptor. Kotlin `object` + `@Component` wird von Spring ab 5.3 als Singleton-Bean ohne Reflection erkannt. Der Orchestrator sammelt diese Descriptors beim Start ein und aggregiert daraus den Katalog aus Abschnitt 1:

```kotlin
interface ToolDescriptor {
    val toolId: String                     // "auth-sms" — frei vergeben, nie aus role/method abgeleitet (öffentlicher API-Vertrag)
    val methodFamily: MethodFamily         // Objekt-Identität statt erneut getippten Strings - verbindet enroll-sms/auth-sms/auth-sms-lookup
    val method: String get() = methodFamily.method  // "sms" - reines Wire-/Storage-Format
    val role: MethodRole                   // IDENTIFICATION | ENROLLMENT | DEVICE_AUTH | LOOKUP_AUTH
    val category: ToolCategory             // IDENT | ENROLL | AUTH — abgeleitet aus role, nie unabhängig gesetzt
        get() = when (role) {
            MethodRole.IDENTIFICATION -> ToolCategory.IDENT
            MethodRole.ENROLLMENT -> ToolCategory.ENROLL
            MethodRole.DEVICE_AUTH, MethodRole.LOOKUP_AUTH -> ToolCategory.AUTH
        }
    val factorTypes: Set<FactorType>
    val maxAcr: String
    val requiresConfirmedEmail: Boolean get() = false
    val allowsMultipleInstances: Boolean get() = false
}
```

`(methodFamily, role)` ist der eigentliche, eindeutige Schlüssel für "das konkrete Verfahren dieser Art für dieses Credential" — `(method, category)` allein ist es **nicht**: `DEVICE_AUTH` und `LOOKUP_AUTH` teilen sich `category=AUTH`. Genau diese Mehrdeutigkeit ersetzt `role` (vormals ein separates `deviceBound: Boolean`, das `category` nicht widersprechen konnte, aber auch nichts über die Eindeutigkeit der Kombination aussagte). `ToolHandlerRegistry` prüft beim Einsammeln der Descriptors, dass kein `(methodFamily, role)`-Paar doppelt vorkommt — ein Duplikat bräche sonst beim erstenmal still auf einen beliebigen Treffer zusammen, statt laut beim Start zu scheitern.

`methodFamily: MethodFamily` (ein bloßer `data class MethodFamily(val method: String)`-Wrapper in `tool_spi`) ersetzt den früher unabhängig in jedem Geschwister erneut getippten String-Literal `"sms"`. `tool_spi` selbst kennt dabei **keine** konkreten Methoden — jedes Modul deklariert seine eigene Instanz (z. B. `auth_sms/Descriptors.kt`: `internal val SMS_METHOD = MethodFamily("sms")`), referenziert von allen eigenen Descriptor-Objekten. Das hält den Katalog bei seinem Grundprinzip (Abschnitt 1: keine zentral gepflegte Liste) — ein neues Modul bringt seine Familie selbst mit, statt eine gemeinsame Datei zu erweitern. `toolId` bleibt bewusst *nicht* aus `(methodFamily, role)` abgeleitet: es ist der öffentliche API-Vertrag (URL-Pfade, Frontend-Routing) und darf nicht von einer internen Umbenennung (z. B. eines `MethodRole`-Namens) mitgerissen werden, auch wenn die aktuellen Werte durchgängig dem Muster `{role-präfix}-{method}[-lookup]` folgen.

`maxAcr`/`factorTypes` sind statisch und dienen der Vorauswahl (kann dieses Tool eine Lücke überhaupt schließen?); was ein konkreter Durchlauf tatsächlich erbracht hat, meldet `Completed` — nie mehr, als der Descriptor zulässt.

Über die Modulgrenze geht ausschließlich `ToolOutcome` — läuft noch, abgeschlossen, oder fehlgeschlagen:

```kotlin
sealed interface ToolOutcome {
    /** Läuft weiter; `data` ist client-gerichtet und wird unverändert als `stepData` durchgereicht. */
    data class InProgress(val nextStep: String, val data: Map<String, Any?>? = null) : ToolOutcome

    /** Versuch fehlgeschlagen; Retry-Regel siehe 04-orchestrierung.md. */
    data class Failed(val reason: String) : ToolOutcome

    /** Abgeschlossen. Die Variante *ist* die Kategorie und legt fest, was der Orchestrator tut. */
    sealed interface Completed : ToolOutcome {
        val amr: List<String>                  // nachgewiesene Methoden, für AuthContext.currentAmr
        val achievedAcr: String?
        val factorTypes: Set<FactorType>        // Teilmenge von ToolDescriptor.factorTypes

        data class Identified(val personId: Long, val auditDetails: Map<String, Any?>? = null, ...) : Completed
        data class Enrolled(val enrollmentRef: EnrollmentRef, val auditDetails: Map<String, Any?>? = null, ...) : Completed
        data class Authenticated(val accountId: Long? = null, ...) : Completed
    }
}
```

- `InProgress.data` ist **client-gerichtet** (der Nutzer muss es sehen, z. B. `missingFields`); `Completed`/`Failed` sind **orchestrator-gerichtet** und werden nie direkt an den Client durchgereicht (siehe [Orchestrierung](04-orchestrierung.md)). Ein Erfolgs-Bool wäre redundant — der Erfolg steckt schon im Typ.
- `amr`/`achievedAcr` liefert jedes Tool selbst, weil dasselbe Verfahren je nach Ausführung unterschiedliche Niveaus erreichen kann.
- `Completed.Authenticated.accountId` setzen nur die `-lookup`-Tools, die den Account selbst auflösen — gewöhnliche `auth-*`-Tools kennen ihn schon über den Kanal.
- Ein Controller pro Tool ruft seinen Handler direkt auf, typisiert statt über eine generische `Map<String, Any?>` — kein `toolId`-basierter Laufzeit-Dispatch ([Projektrahmen](08-projektrahmen.md) A11: „Lesbarkeit hat Vorrang vor maximal generischem API-Wiring"). Dieser Controller lebt im selben Modul wie sein Handler, nicht im Orchestrator (Abschnitt 4). Referenzen, die für ein Tool aufgelöst werden (z. B. `EnrollmentRef`), werden am Aufrufort im Controller aufgelöst und geprüft — der Handler bekommt nie einen nullable Parameter, sondern entweder einen gültigen Wert oder der Aufruf bricht vorher ab.
- **Die bestätigte E-Mail ist die eine Ausnahme.** Sie ist kein austauschbares Credential, sondern der Konto-*Identifikator*: `auth-sms-lookup`/`auth-password-lookup` lösen darüber ein Konto auf, ohne dass `auth_email` beteiligt wäre, und `requiresConfirmedEmail` hängt daran. Deshalb liegt sie direkt auf `Account` (Unique-Index, V6) — und deshalb schreibt `auth_email` sie **selbst** dorthin, über eine deklarierte `allowedDependencies`-Kante auf `account` ([Projektrahmen](08-projektrahmen.md)). Vorher trug der generische `ToolOutcomeProcessor` dafür einen `if (method == "email")`-Zweig und das Modul bekam Konto-Wissen als vorgekaute Handler-Parameter gereicht: dieselbe Kopplung, nur unsichtbar und in der Schicht, die alle Verfahren gleich behandeln soll. Ein zweites Modul mit demselben Bedarf wäre ein Anlass zu prüfen, ob das Attribut überhaupt auf `Account` gehört — kein Präzedenzfall.

---

## 3) Modulinterne Flow-Architektur (optional)

Ein Methodenmodul darf sich intern frei organisieren — nur `ToolOutcome` verlässt die Modulgrenze, nie ein interner Zustand. Ein mögliches, aber nicht vorgeschriebenes Muster: ein reiner `Flow`, der nur seinen eigenen `State` kennt und aus `(State, Input)` eine `Decision` mit nächstem Zustand, auszuführenden Effekten (z. B. „TAN senden") und einem neutralen `FlowOutcome` (`InProgress`/`Completed`/`Failed`) ableitet. Der Handler übersetzt `FlowOutcome` in `ToolOutcome`. Vorteil: Seiteneffekte bleiben im Modul, der `State` wird nie sichtbar, und Flows lassen sich austauschen, ohne die Prozess-Ebene zu berühren.

---

## 4) Wo der Controller lebt: `tool_api` als Modulgrenze

Der `@RestController` eines Tools lebt **im selben Modul wie sein Handler** (z. B. `id_fsc.api.v1.IdentFscToolController`, `auth_sms.api.v1.AuthSmsToolController`), nicht im `orchestrator`. Der Orchestrator kennt kein einziges Methodenmodul namentlich — `orchestrator/ModuleMetadata.kt` deklariert `allowedDependencies = ["tool_spi", "tool_api", "account", "ext_stammdaten"]`, ohne `id_fsc`, `auth_sms`, `auth_password`, `auth_email` oder `auth_device`.

Ermöglicht wird das durch das gemeinsame SPI-Modul `tool_api` (`allowedDependencies = ["tool_spi"]`), das beide Seiten kennen dürfen:

- **`ToolEndpoint`** — Session-/Journey-Mechanik, die jeder Tool-Controller braucht (Aktivierung starten, Kontext laden, Ergebnis anwenden, Journey abbrechen). Implementiert von `ToolControllerSupport` im `orchestrator`; von jedem Tool-Controller als Konstruktor-Abhängigkeit injiziert.
- **`AccountDirectory`** / **`PersonDirectory`** / **`DeviceProofs`** — schmale Lese-/Prüf-Ports auf Konto-, Personen- und Geräte-Nachweis-Daten, die einzelne Tools brauchen (z. B. `auth-sms-lookup` zum Auflösen eines Kontos über E-Mail). Implementiert von `AccountService` (`account`), `ExtStammdatenService` (`ext_stammdaten`) bzw. `DeviceProofValidator` (`orchestrator`) — jeweils direkt am Domänenservice, ohne separate Adapterklasse.
- **Envelope-DTOs** (`ChannelResponse`, `ChannelBlock`, `ActiveMethodView`, `Next`, `DemoInfo`) — die gemeinsame Antwortform, die jeder Tool-Controller zurückgibt.
- **`ToolSwitchController`** — der einzige generische, toolId-lose Controller (Tool wechseln/abbrechen ohne tool-spezifische Logik); lebt deshalb direkt in `tool_api` statt in einem einzelnen Methodenmodul.

Die Abhängigkeit zeigt in beide Richtungen auf dasselbe Interface, nie direkt aufeinander: ein Methodenmodul ruft `ToolEndpoint`-Methoden auf, ohne `ToolControllerSupport` oder den `orchestrator` zu kennen; der `orchestrator` liest nie einen Handler eines Methodenmoduls. Die HTTP-Pfade (`/orchestrator/api/v1/tools/...`) sind dabei unverändert — Spring routet nach `@RequestMapping`, nicht nach Kotlin-Package, ein Endpunkt-URL verrät also nicht, in welchem Modul sein Controller liegt. Details zur Modulliste und den Abhängigkeitsrichtungen: [Projektrahmen](08-projektrahmen.md) Abschnitt 3.
