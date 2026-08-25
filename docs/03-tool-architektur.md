# Tool-Architektur

Ein *Tool* ist ein konkretes Verfahren zur Identifikation, zum Enrollment oder zur Authentifizierung
(`ident-fsc`, `enroll-sms`, `auth-sms`). Dieses Dokument beschreibt, wie Tools sich selbst
beschreiben und was sie über die Modulgrenze hinweg melden.

Was der Orchestrator mit diesen Meldungen macht, steht in [04-orchestrierung.md](04-orchestrierung.md).

---

## 1) Tool-Katalog

`ToolSession` ist die dritte und kurzlebigste Session-Ebene (`ChannelSession` -> `ProcessSession` -> `ToolSession`) und steht für genau einen Durchlauf eines Tools; sie trägt nur technische Lifecycle-Metadaten, keine Kind-Subtypen. `toolId` (z. B. `ident-fsc`, `enroll-sms`, `auth-sms`) identifiziert Kind und Methode zusammen in einem flachen Bezeichner — kein persistiertes Feld, sondern ergibt sich aus der Route und wählt darüber Handler und Moduldaten-Klasse.

Der Tool-Katalog ist **keine zentral gepflegte Tabelle**, sondern die Aggregation der Selbstauskünfte aller Module (`ToolDescriptor`, Abschnitt 2):

| toolId | Kategorie | method | factorTypes | maxAcr | deviceBound |
|---|---|---|---|---|---|
| `ident-fsc` | Identifikation | `fsc` | `{possession}` | `loa2` | — |
| `enroll-sms` / `auth-sms` | Enrollment / Auth | `sms` | `{possession}` | `loa1` | `true` |
| `enroll-password` / `auth-password` | Enrollment / Auth | `password` | `{knowledge}` | `loa1` | `true` |
| `enroll-email` / `auth-email` | Enrollment / Auth | `email` | `{possession}` | `loa1` | `true` |
| `auth-sms-lookup` / `auth-password-lookup` / `auth-email-lookup` | Auth | `sms`/`password`/`email` | wie Zwilling | `loa1` | `false` |
| `enroll-device` / `auth-device` | Enrollment / Auth | `device` | `{possession,knowledge,inherence}` | `loa2` | `true` |

Entscheidungen dahinter:

- Jedes Modul liefert Kategorie/Methode/Faktorart/Niveau selbst — es weiß am besten, was sein Verfahren maximal trägt. Ein neues Modul bringt seine Beschreibung mit; niemand muss eine zentrale Liste nachpflegen.
- `method` wird **nicht** aus `toolId` geparst (der Name ist ein Bezeichner, kein strukturiertes Datum) und trägt echte Information: `enroll-sms`/`auth-sms` melden dieselbe `method`, worüber ein Auth-Tool die passende Zeile in `account.authenticationMethods` findet.
- `factorTypes` ist die Grundlage für MFA-Prüfungen ([Orchestrierung](04-orchestrierung.md)) — eine **Menge**, weil ein einzelnes Verfahren mehrere Faktoren zugleich erbringen kann. `enroll-device`/`auth-device` ist genau der Fall, der hier lange nur als Hypothese stand ("ein hypothetischer Passkey mit User Verification wäre Besitz *und* Inhärenz und erfüllte MFA im Alleingang"): ein gerätegebundenes, nicht-extrahierbares Schlüsselpaar, dessen Nutzung durch einen (im Demo gemockten) System-PIN/Biometrie-Prompt gattet ist, meldet direkt `loa2` und zwei Faktorarten aus einem einzigen Durchlauf — Besitz des Schlüssels plus Wissen (PIN) oder Inhärenz (Biometrie), je nachdem, was der Prompt pro Versuch bestätigt (nicht fest beim Enrollment verdrahtet — reale Geräte legen sich darauf auch nicht fest).
- `requiresConfirmedEmail` (bislang nur `enroll-password`) wird an zwei Stellen unabhängig geprüft: bei der Kandidatenermittlung (`AuthPolicy.enrollmentCandidates`) und nochmals bei der Aktivierung selbst (`ToolControllerSupport.validatePreconditions`) — letzteres, weil die Aktivierungsprüfung sonst nur die Tool-*Kategorie* gegen den Auswahlkontext prüft, nicht den konkreten Kandidaten, und einen direkten Aufruf unter Umgehung der Kandidatenliste sonst durchließe.
- `deviceBound=false` markiert die `-lookup`-Zwillinge: Sie melden bewusst dieselbe `method` wie ihr geräte-gebundenes Geschwister (dasselbe Credential, nur ein anderer Weg, es zu präsentieren) und lösen den Account selbst über eine eingegebene E-Mail auf, statt ihn schon über den Kanal zu kennen ([Orchestrierung](04-orchestrierung.md) Abschnitt 4). Ohne den Filter könnte die normale Kandidatenermittlung für eine ganz gewöhnliche, bereits Account-gebundene Session mehrdeutig auf den `-lookup`-Zwilling statt das Original treffen.

Zentral bleibt nur, was ein einzelnes Modul nicht wissen *kann*: welches Niveau sich aus einer **Kombination** von Nachweisen ergibt, und welches Niveau eine Ressource fordert — Sache der `AuthPolicy` ([Orchestrierung](04-orchestrierung.md)).

---

## 2) `ToolDescriptor` und `ToolOutcome`

Jeder Handler beschreibt sich selbst; der Orchestrator sammelt diese Descriptors beim Start ein und aggregiert daraus den Katalog aus Abschnitt 1:

```kotlin
interface ToolDescriptor {
    val toolId: String                     // "auth-sms"
    val category: ToolCategory             // IDENT | ENROLL | AUTH
    val method: String                     // "sms" — verbindet enroll-sms und auth-sms
    val factorTypes: Set<FactorType>
    val maxAcr: String
    val requiresConfirmedEmail: Boolean get() = false
    val deviceBound: Boolean get() = true
}
```

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
- Ein Controller pro Tool ruft seinen Handler direkt auf, typisiert statt über eine generische `Map<String, Any?>` — kein `toolId`-basierter Laufzeit-Dispatch ([Projektrahmen](08-projektrahmen.md) A11: „Lesbarkeit hat Vorrang vor maximal generischem API-Wiring"). Referenzen, die der Orchestrator für ein Tool auflöst (z. B. `EnrollmentRef`), werden am Aufrufort im Controller aufgelöst und geprüft — der Handler bekommt nie einen nullable Parameter, sondern entweder einen gültigen Wert oder der Aufruf bricht vorher ab.

---

## 3) Modulinterne Flow-Architektur (optional)

Ein Methodenmodul darf sich intern frei organisieren — nur `ToolOutcome` verlässt die Modulgrenze, nie ein interner Zustand. Ein mögliches, aber nicht vorgeschriebenes Muster: ein reiner `Flow`, der nur seinen eigenen `State` kennt und aus `(State, Input)` eine `Decision` mit nächstem Zustand, auszuführenden Effekten (z. B. „TAN senden") und einem neutralen `FlowOutcome` (`InProgress`/`Completed`/`Failed`) ableitet. Der Handler übersetzt `FlowOutcome` in `ToolOutcome`. Vorteil: Seiteneffekte bleiben im Modul, der `State` wird nie sichtbar, und Flows lassen sich austauschen, ohne die Prozess-Ebene zu berühren.
