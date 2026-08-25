# Konkrete Abläufe

Wie `ident-fsc`/`auth-sms`/`enroll-sms` die Bausteine aus [03-tool-architektur.md](03-tool-architektur.md)
und [04-orchestrierung.md](04-orchestrierung.md) konkret nutzen — mit Fokus auf das Datenmodell
und die Entscheidungen dahinter. Ein durchgängiges Call-Beispiel steht in [05-api.md](05-api.md).

---

## 1) Datenmodell für `auth-sms` und `enroll-sms`

```mermaid
classDiagram
  class Account {
    long id
    long personId
    string email
    Instant emailConfirmedAt
    json identifications
    json authenticationMethods
  }
  class AuthenticationMethodEntry {
    string method
    bool active
    string enrolledUnderAcr
    json details
  }
  class EnrollmentRef {
    string type
    string id
  }
  class AuthSmsEnrollment {
    long id
    string phoneNumber
  }

  Account "1" --> "0..*" AuthenticationMethodEntry : authenticationMethods
  AuthenticationMethodEntry "1" --> "0..1" EnrollmentRef : details.enrollmentRef
  EnrollmentRef --> AuthSmsEnrollment : resolved by (type=auth_sms_enrollment, id)
```

Entscheidungen, die an diesem Modell hängen:

- **`enrolledUnderAcr` als eigenes Feld, nicht nur Audit-Inhalt**: Eine Methode darf bei der Authentifizierung nicht mehr Vertrauen erzeugen, als bei ihrer Einrichtung vorhanden war — das effektive `achievedAcr` eines `auth-*`-Tools ist durch `enrolledUnderAcr` der verwendeten Methode gedeckelt ([Orchestrierung](04-orchestrierung.md) Abschnitt 1). Ohne diese Regel gäbe es einen Eskalationspfad: Wer eine schwache Session übernimmt, hinterlegt dort eine eigene Methode und erreicht damit dauerhaft ein höheres Niveau, als er je nachgewiesen hat. Den Wert kennt nur der Orchestrator (aus dem `AuthContext` zum Enrollment-Zeitpunkt), nie das Modul.
- **`enrolledUnderAmr` daneben**: eigener Zweck, keine Dopplung — stellt sich eine Methode später als kompromittiert heraus, lassen sich damit alle Methoden finden, die *unter* ihr eingerichtet wurden.
- **`email`/`emailConfirmedAt` direkt auf `Account`**, nicht in `authenticationMethods[].details`: Ein Account hat höchstens eine bestätigte E-Mail zu jeder Zeit, dieselbe Behandlung wie `personId`. Erlaubt, dieselbe Adresse sowohl als Auth-Mittel (`enroll-email`/`auth-email`) als auch als Identifikator für den lookup-basierten Login zu nutzen. Ein eindeutiger Index verhindert doppelt vergebene, bereits bestätigte Adressen.
- **Kein eigenes Identifikator-Feld bei `enroll-password`/`auth-password`**: Die bestätigte `account.email` übernimmt diese Rolle, erzwungen über `ToolDescriptor.requiresConfirmedEmail` ([Tool-Architektur](03-tool-architektur.md) Abschnitt 2).
- **Keine TAN im Enrollment**: Die ausgestellte TAN ist ein versuchsbezogenes Einmalgeheimnis und liegt gehasht mit Ablaufzeit in der Tool-Session-Tabelle, nicht im langlebigen Enrollment-Datensatz — sonst würden sich zwei parallele Versuche gegenseitig die TAN überschreiben. Die eingereichte TAN wird nirgends gespeichert, nur gegen den Hash geprüft.
- **Orchestrator speichert nur Lifecycle/Routing**, nie Fach- oder Moduldaten — die liegen ausschließlich im jeweiligen Methodenmodul (`auth_sms_use_tool_data`/`enroll_sms_tool_data` bei SMS).

Regel für `identifications[].details`: Der Eintrag belegt, **dass und wie** geprüft wurde, nicht **was** geprüft wurde. Hinein gehören Nachweisanker (`provider`, `providerTxId`), Verfahrensversion und ein Hash über die geprüften Merkmale; nicht hinein gehören KVNR/Name im Klartext (die hängen über `personId` an der Person) oder Geheimnisse.

---

## 2) `ident-fsc`

`id_fsc` prüft `kvnr`/`name`/`vorname`/`fsc` gegen den FSC-Dienst und löst dabei die Identität auf — das *ist* die fachliche Leistung des Moduls. Das `account`-Modul kennt `id_fsc` nicht; die Verknüpfung übernimmt erst der Orchestrator beim Verarbeiten von `Completed.Identified` ([Orchestrierung](04-orchestrierung.md)).

Besonderheiten gegenüber dem allgemeinen Muster in [05-api.md](05-api.md): Zwei `PATCH`-Aufrufe (erst `kvnr`/`name`/`vorname`, dann `fsc`) — der FSC-Dienst wird erst aufgerufen, wenn alle vier Felder vorliegen. `GET` baut `stepData` bei jedem Aufruf neu aus den Moduldaten auf; ist das Tool bereits abgeschlossen, zeigt die Antwort bereits auf das Folge-Tool (Resume-Fall).

---

## 3) `auth-sms` (und `auth-password`/`auth-email` analog)

Der Orchestrator liest die aktive Enrollment-Referenz des Accounts (`details.enrollmentRef`) und übergibt sie an den Handler — `auth_sms` referenziert `account` nicht selbst, sondern bekommt eine opake `EnrollmentRef` gereicht (Modulith-Grenze, [Projektrahmen](08-projektrahmen.md)). Das gilt für jedes Methodenmodul außer `auth_email`, das als einzige deklarierte Ausnahme direkt auf `account` zugreifen darf ([Tool-Architektur](03-tool-architektur.md) Abschnitt 2). `auth_sms` löst die Referenz auf ein bestehendes Enrollment auf, erzeugt/versendet/prüft die TAN, verändert das Enrollment aber nie.

Fehlerfall zusätzlich zum allgemeinen Vertrag ([Betrieb](07-betrieb.md)): unbekannte `enrollmentRef` oder fehlendes Enrollment -> `422`.

---

## 4) `enroll-sms` (und `enroll-password`/`enroll-email` analog)

Wie `auth-sms`, aber der `AuthSmsEnrollment`-Datensatz entsteht hier neu — und zwar erst **nach** erfolgreicher TAN-Prüfung, nie beim ersten `PATCH` mit der Telefonnummer (die ist ja noch unbestätigt). Nach Abschluss legt der Orchestrator gemäß [Orchestrierung](04-orchestrierung.md) Abschnitt 1 den `account.authenticationMethods`-Eintrag an (inkl. `enrolledUnderAcr` aus dem aktuellen `AuthContext`).

Fehlerfall zusätzlich zum allgemeinen Vertrag: ungültige Telefonnummer (Formatfehler) -> `400`.

---

## 5) `enroll-device` / `auth-device`

Anders als `sms`/`email`/`password` gibt es kein serverseitig ausgestelltes Geheimnis (keine TAN, kein Code): das Credential *ist* ein auf dem Gerät erzeugtes, nicht-extrahierbares ECDSA-P-256-Schlüsselpaar, unabhängig vom DPoP-Kanal-Schlüssel. Der Client weist Besitz nach, indem er einen selbstsignierten `device-proof+jwt` erzeugt — strukturell identisch zu einem DPoP-Proof (`jwk` im Header, `htm`/`htu`/`iat`/`jti`), aber mit eigenem `typ` und einem zusätzlichen `accessMeans`-Claim (`pin` oder `biometric`), den der (im Demo gemockte) System-PIN/Biometrie-Prompt pro Versuch bestimmt. `DeviceProofValidator` prüft ihn serverseitig eigenständig (bewusst kein Ausbau von `DpopValidator` — zwei kleine, unabhängig lesbare Prüfungen statt eine generisch gemachte, [Projektrahmen](08-projektrahmen.md) A11), verwendet dafür aber dieselben generischen Bausteine (`JwkThumbprintService`, Replay-Schutz per Thumbprint+`jti`).

Kein Server-Nonce nötig: `htu` bindet den Proof bereits an die konkrete, einmalige `toolSessionId`-URL — dasselbe Modell, das gewöhnliche DPoP-Proofs in dieser App schon verwenden.

- **`enroll-device`**: Der Controller validiert den Proof, reicht nur die verifizierten Public-Key-Felder (`DevicePublicKey`: `kty`/`crv`/`x`/`y`/`thumbprint`, nicht die rohe `JWK`) an den Handler weiter — das Modul bekommt nie ein Nimbus-/Krypto-Objekt, nur Strings ([Tool-Architektur](03-tool-architektur.md) Abschnitt 2). Legt einen neuen `device_enrollment`-Datensatz an; `EnrollmentRef(type="device_enrollment", id=...)`.
- **`auth-device`**: Löst die aktive Enrollment-Referenz auf (wie `auth-sms`), vergleicht den Thumbprint des präsentierten Schlüssels mit dem gespeicherten — bei Abweichung `Failed("Geraet nicht erkannt")`, ohne zu verraten, welches Gerät stattdessen erwartet wurde.
- **loa2 in einem Schritt**: `maxAcr=loa2`, `factorTypes={possession,knowledge,inherence}` — Besitz des Schlüssels plus Wissen (PIN) oder Inhärenz (Biometrie) aus demselben Durchlauf, der bislang nur hypothetische Passkey-Fall aus [03-tool-architektur.md](03-tool-architektur.md) Abschnitt 1. Die loa2-Voraussetzung für ein Enrollment (`enrolledUnderAcr` darf nicht höher liegen als das, was die Session tatsächlich schon bewiesen hat) ist bereits durch bestehende Gates abgedeckt, nicht durch neuen Code: `ident-fsc` liefert während REGISTRATION immer zuerst `loa2`, und `MANAGE_METHODS_REQUIRED_ACR=loa2` erzwingt denselben Nachweis vor jedem nachträglichen Enrollment.

Fehlerfall zusätzlich zum allgemeinen Vertrag: fehlender/ungültiger `deviceProof` (Signatur, Replay, `htm`/`htu`/`iat`) -> `401` (derselbe `DpopValidationException`-Pfad wie bei DPoP-Proofs); falscher Schlüssel bei `auth-device` -> `Failed`, kein Fehlerstatus (Retry-Fall wie bei falscher TAN).
