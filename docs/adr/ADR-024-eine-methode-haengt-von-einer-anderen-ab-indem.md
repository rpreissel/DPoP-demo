# ADR-24: Eine Methode hängt von einer anderen ab, indem sie deren Angabe verlangt

> **Stand 2026-09-23:** Die tatsächlich genutzte Kette ist länger als unten beschrieben; siehe
> Nachtrag.

**Entscheidung** (**umgesetzt**): Abhängigkeiten zwischen Verfahren brauchen keine eigenen
Begriffe. Ein Modul schreibt beim Einrichten einen Claim, ein anderes verlangt ihn per
`ClaimRequirement` — und `requires` entscheidet damit nicht mehr nur über das **Angebot**, sondern
gilt **dauerhaft**: Fällt die Angabe weg, fällt das Credential, das sie verlangte, mit. Das setzt
sich über alles fort, was seinerseits daran hängt, bis sich nichts mehr ändert
(`JourneyActionExecutor.dependentsOfLostClaims`).

Die heute tatsächlich genutzte Kette beginnt bei der Adresse: `enroll-password` verlangt `ClaimRequirement(EMAIL,
PROVEN)`, also nimmt eine zurückgenommene Adresse das Passwort mit. `enroll-password` behauptet
zusätzlich `PASSWORD_EXISTS`, einen Claim, den derzeit **niemand** verlangt. Er bleibt trotzdem
deklariert: Mit ihm ließe sich eine Abhängigkeit vom Passwort ausdrücken, und die Alternative wäre,
beim nächsten Bedarf einen zweiten Mechanismus daneben zu stellen.

Damit das überhaupt greifen kann, hat der Widerruf einen dritten Auslöser bekommen: ein
Attribut lässt sich jetzt **direkt** zurücknehmen (`AccountService.retractAttribute`,
`DELETE /channels/{id}/attributes/{attribute}`). Vorher konnte eine bestätigte Adresse gar nicht
verloren gehen: `confirm-email` schreibt seine Angabe als ATTESTATION, also ohne `auth_method_id`, und
EMAIL gehört ohnehin dem Konto selbst. Kein Widerruf eines Verfahrens erreichte sie.

**Erwogene Alternative**: Eine eigene Descriptor-Eigenschaft `dependsOnMethods: Set<String>`, die
Methodennamen nennt. Zuerst so gebaut und wieder zurückgenommen. Sie hätte im direkten Fall
dasselbe geleistet, aber neben dem Claim-Modell eine zweite Art eingeführt, Abhängigkeiten
auszudrücken. Und die Kette über die Adresse hätte sie gar nicht erfasst, weil dort kein Verfahren
beteiligt ist.

**Zurückgenommen**: `enroll-kobil` verlangte zunächst `PASSWORD_EXISTS`, das Kontopasswort war also
Pflicht für eine KOBIL-Bindung. Das war eine fachliche Entscheidung, keine technische, und zwar die
falsche: Es machte ein Verfahren von einem anderen abhängig, ohne dass der Ablauf das verlangt, und
sperrte KOBIL aus jeder Registrierung aus, die noch kein Passwort angelegt hatte. Der Mechanismus
blieb, die Kopplung fiel. Was an ihre Stelle trat, steht in ADR-21: `auth-kobil` bietet nur die
Entsperrwege an, die es für dieses Credential wirklich gibt.

**Zweite erwogene Alternative**: Beim Widerruf einfach alle aktiven Methoden neu gegen ihr
`requires` prüfen. Genau das passiert. Nur muss die Vorhersage dafür genau mit dem Schreiben übereinstimmen: Welche
Typen eine widerrufene Instanz mitnimmt, ermittelt `AccountService.claimedTypesOf` mit **derselben**
Abfrage und demselben `MethodModule`-Filter wie der Widerruf selbst. Eine Vorhersage, die vom
Schreibvorgang abweicht, wäre schlimmer als keine.

**Was das kostet:**
- `requires` bedeutet jetzt mehr als vorher, und jede bestehende Anforderung bekommt diese neue
  Bedeutung. Heute ist das unkritisch, weil die einzige Anforderung an einen Anker (`EMAIL`) nur über
  den neuen, ausdrücklichen Endpunkt zum Zurücknehmen verloren gehen kann, nie aus Versehen. Das
  Ersetzen eines Ankers („anker-ersetzt“) behauptet im selben Schritt den neuen Wert und löst deshalb
  nichts aus.
- Ein Attribut zurückzunehmen hat damit weite Folgen: Die Adresse nimmt das Passwort mit. Das
  ist gewollt und wird vorher geprüft: Die Prüfung des Mindestniveaus rechnet **mit** allem, was
  mit entfällt, und eine Ablehnung nennt es beim Namen.
- `AttributeType` trägt mit `PASSWORD_EXISTS` erstmals eine Aussage, die nichts über die **Person**
  sagt, sondern über die Credentials des Kontos. Bewusst dort und nicht in einem zweiten
  Mechanismus: Das Claim-Log verwaltet ohnehin genau die Lebensdauer, um die es hier geht.

**Nachtrag (2026-09-23)**: Neben `enroll-password` verlangt seit ADR-17 auch `enroll-email`
`ClaimRequirement(EMAIL, PROVEN)` (`auth_email/Descriptors.kt`). Eine zurückgenommene Adresse nimmt
also Passwort **und** E-Mail-Login mit. Die Aussage „die einzige Anforderung an einen Anker“ unter
„Kosten“ ist damit überholt: `EMAIL` wird an zwei Stellen verlangt, und `ident-kvnr` verlangt
zusätzlich `NAME`/`VORNAME`/`GEBURTSDATUM` (ADR-18). Dort ist es allerdings nur eine Bedingung dafür,
ob der Schritt angeboten wird, und keine Voraussetzung eines Credentials.

**Nachtrag (ADR-34, 2026-09-24)**: Es gibt einen vierten Auslöser mit eigenem Anker: Ändert oder
entfernt das Personenverzeichnis KVNR oder Versicherungsnummer, nimmt
`AccountService.applyDirectoryChange` den alten Wert mit `RetractionAnchor.PERSON_DIRECTORY` zurück. Das ist kein Tool; die Regel „ein Tool
widerruft nie“ bleibt unberührt.
