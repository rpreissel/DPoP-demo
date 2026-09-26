# ADR-12: Ein Widerruf ist eine eigene Zeile mit eigenem Vertrauensanker

**Status**: umgesetzt.

**Entscheidung**: Ein zurückgenommener Wert wird in einer eigenen Zeile festgehalten:
`account.retraction(account_id, attribute_type, normalized_value, trust_anchor, reason, retracted_at)`.
Diese Zeile ist selbst eine Angabe mit eigenem Vertrauensanker: **wer** den Wert zurücknimmt, dazu
Grund und Zeitpunkt. Das Log der Angaben (`account.claim`) wird nur ergänzt, nie geändert. Welcher
Wert gilt, ergibt sich aus „Angaben minus Widerrufe“. Ist der Wert ein Anker, wird zusätzlich seine
Zeile in `account.anchor` gelöscht; diese Tabelle hält den aktuellen Stand und ist kein Log.

Ein Widerruf entkräftet nur Angaben, die **vor** ihm liegen (`retractedAt >= establishedAt` in
`AccountClaimRepository.findEstablished`). Ein danach neu bestätigter Wert gilt wieder; die Folge
a → b → a endet bei a. Diese eine Abfrage bildet die Differenz; alle Stellen, die gültige Werte
brauchen, rufen sie auf.

**Die Auslöser eines Widerrufs** (die vollständige Liste, andere ADRs verweisen hierher):

| Auslöser | Was zurückgenommen wird | Vertrauensanker |
|---|---|---|
| Ein Verfahren wird entfernt (`AccountDeletionService.revokeMethod` → `retractClaimsOf`) | die Angaben dieser Methodeninstanz, aber nur die mit `AttributeAuthority.MethodModule` (etwa `PHONE_NUMBER`, `PASSWORD_EXISTS`) | `ACCOUNT_MANAGEMENT` |
| Ein Anker wird an derselben Stelle ersetzt (`EMAIL`, `EID_RESTRICTED_ID`, `VERSNR`) | der alte Wert, Grund „anker-ersetzt“ ([ADR-19](ADR-019-aufloesung-nur-ueber-anker-die-eid-restricted-id.md)) | `ACCOUNT_MANAGEMENT` |
| Ein Attribut wird direkt zurückgenommen (`AccountService.retractAttribute`, `DELETE /channels/{id}/attributes/{attribute}`) | das Attribut samt Anker-Zeile ([ADR-24](ADR-024-eine-methode-haengt-von-einer-anderen-ab-indem.md)) | `ACCOUNT_MANAGEMENT` |
| Das Personenverzeichnis meldet eine neue oder entfernte KVNR bzw. Versicherungsnummer (`AccountService.applyDirectoryChange`) | der alte Wert ([ADR-34](ADR-034-personenverzeichnis-meldet-aenderungen.md)) | `PERSON_DIRECTORY` |

Der dritte Anker, `OPERATOR`, ist für Eingriffe eines Menschen vorgesehen; heute gibt es keinen
Aufrufer. Widerrufe kommen **nie** über den Vertrag der Tools: `ToolOutcome` kennt nur positive
Ergebnisse, und der Vertrauensanker eines Widerrufs ist deshalb bewusst ein eigener Typ
(`RetractionAnchor`) und keine `ClaimSource`.

Damit ein Verfahren weiß, was es beim Entfernen mitnimmt, trägt jede Zeile im Log die
`auth_method_id` der Methodeninstanz, bei deren Einrichtung sie entstand (`null` bei Tools zur
Identifizierung). Anker und Stammdaten bleiben über das Credential hinaus bestehen; sonst hätte das
Entfernen des E-Mail-Verfahrens die bestätigte Adresse und damit die Anmeldung mit Passwort gekostet.

Das Log protokolliert **Änderungen, nicht Durchläufe**: `recordClaims` überspringt eine Angabe, die in
gleicher Form schon gilt (gleicher Typ, normalisierter Wert, Quelle und Methodeninstanz). Die
Methodeninstanz gehört zu diesem Vergleich; ein bekannter Wert, den ein neues Verfahren einrichtet,
wird deshalb trotzdem protokolliert.

**Erwogene Alternative**: Markierungsspalten (`retracted_at`/`retracted_by`) direkt in der Zeile der
Angabe. Das ergäbe eine einzige Tabelle und die einfachste Abfrage, wäre aber die einzige Stelle, an
der eine Zeile im Log nachträglich geändert wird.

**Begründung**: Das Log ist die maßgebliche Quelle und wird nie überschrieben. Diese Regel darf keine
Ausnahme bekommen, denn jede Änderung an einer bestehenden Zeile macht es schwerer, nachträglich zu
sagen, was wann galt. Eine Widerrufszeile belegt ihre Herkunft genauso wie eine Angabe und hält den
Vertrag der Tools frei von negativen Ergebnissen.

**Folgen und Kosten**: Zwei Formen statt einer. Der gültige Wert ist immer eine Differenz über zwei
Tabellen. Der Widerruf macht einen Wert nur ungültig, er löscht ihn nicht; mit dem normalisierten Wert
steht in der Widerrufszeile sogar eine zweite lesbare Kopie. Eine Aufbewahrungsfrist, nach der beide
Zeilen gemeinsam gelöscht werden, ist bewusst nicht mitentschieden
([Idee: Verschlüsselung und Aufbewahrung](../ideen/verschluesselung-differenzierte-aufbewahrung.md)).
Der Nachweis für das Audit hängt nicht daran: `account.change_log` (IDENTIFIED) hält Verfahren, Niveau und
Zeitpunkt ohne die Werte fest.

**Geschichte**: Anfangs hielten abgeleitete Spalten am Konto den aktuellen Wert, und nur eine Stelle
las Widerrufe. Mit [ADR-14](ADR-014-schema-zusammengefuehrt-das-konto-als-sperrpunkt-eine-wahrheit.md)
entfielen die abgeleiteten Spalten. Die Auslöser kamen nacheinander hinzu: das Ersetzen eines Ankers
mit ADR-19, der direkte Widerruf mit ADR-24, die Meldung des Personenverzeichnisses mit ADR-34.
