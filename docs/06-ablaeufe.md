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

Der Orchestrator liest die aktive Enrollment-Referenz des Accounts (`details.enrollmentRef`) und übergibt sie an den Handler — **nur der Orchestrator referenziert `account`**, nie das Methodenmodul selbst (Modulith-Grenze, [Projektrahmen](08-projektrahmen.md) A11). `auth_sms` löst die Referenz auf ein bestehendes Enrollment auf, erzeugt/versendet/prüft die TAN, verändert das Enrollment aber nie.

Fehlerfall zusätzlich zum allgemeinen Vertrag ([Betrieb](07-betrieb.md)): unbekannte `enrollmentRef` oder fehlendes Enrollment -> `422`.

---

## 4) `enroll-sms` (und `enroll-password`/`enroll-email` analog)

Wie `auth-sms`, aber der `AuthSmsEnrollment`-Datensatz entsteht hier neu — und zwar erst **nach** erfolgreicher TAN-Prüfung, nie beim ersten `PATCH` mit der Telefonnummer (die ist ja noch unbestätigt). Nach Abschluss legt der Orchestrator gemäß [Orchestrierung](04-orchestrierung.md) Abschnitt 1 den `account.authenticationMethods`-Eintrag an (inkl. `enrolledUnderAcr` aus dem aktuellen `AuthContext`).

Fehlerfall zusätzlich zum allgemeinen Vertrag: ungültige Telefonnummer (Formatfehler) -> `400`.
