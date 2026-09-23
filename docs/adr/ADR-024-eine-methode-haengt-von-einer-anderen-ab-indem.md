# ADR-24: Eine Methode hängt von einer anderen ab, indem sie deren Angabe verlangt

**Entscheidung** (**umgesetzt**): Abhängigkeiten zwischen Verfahren brauchen keine eigenen
Begriffe. Ein Modul schreibt beim Einrichten einen Claim, ein anderes verlangt ihn per
`ClaimRequirement` — und `requires` entscheidet damit nicht mehr nur über das **Angebot**, sondern
gilt **dauerhaft**: Fällt die Angabe weg, fällt das Credential, das sie verlangte, mit. Das wirkt
weiter über alles, was seinerseits daran hängt, bis sich nichts mehr ändert
(`JourneyActionExecutor.dependentsOfLostClaims`).

Die heute lebende Kette ist die Adresse: `enroll-password` verlangt `ClaimRequirement(EMAIL,
PROVEN)`, also nimmt eine zurückgenommene Adresse das Passwort mit. `enroll-password` behauptet
zusätzlich `PASSWORD_EXISTS` — ein Claim, den derzeit **niemand** verlangt. Er bleibt trotzdem
deklariert: Mit ihm ließe sich eine Abhängigkeit vom Passwort ausdrücken, und die Alternative wäre,
beim nächsten Bedarf einen zweiten Mechanismus daneben zu stellen.

Damit das überhaupt greifen kann, hat der Widerruf einen dritten Auslöser bekommen: ein
Attribut lässt sich jetzt **direkt** zurücknehmen (`AccountService.retractAttribute`,
`DELETE /channels/{id}/attributes/{attribute}`). Vorher konnte eine bestätigte Adresse gar nicht
verloren gehen — `confirm-email` schreibt seine Angabe als ATTESTATION, also ohne
`auth_method_id`, und EMAIL gehört ohnehin dem Konto selbst; kein Methodenwiderruf erreichte sie.

**Erwogene Alternative**: Eine eigene Descriptor-Eigenschaft `dependsOnMethods: Set<String>`, die
Methodennamen nennt. Zuerst so gebaut und wieder zurückgenommen. Sie hätte dasselbe für den
direkten Fall geleistet, aber eine zweite Abhängigkeitssprache neben dem Claim-Modell eingeführt —
und die Kette über die Adresse hätte sie gar nicht erfasst, weil dort keine Methode beteiligt ist.

**Zurückgenommen**: `enroll-kobil` verlangte zunächst `PASSWORD_EXISTS`, das Kontopasswort war also
Pflicht für eine KOBIL-Bindung. Das war eine Verfahrensentscheidung, keine technische — und die
falsche: Es machte ein Verfahren von einem anderen abhängig, ohne dass der Ablauf das verlangt, und
sperrte KOBIL aus jeder Registrierung aus, die noch kein Passwort angelegt hatte. Der Mechanismus
blieb, die Kopplung fiel. Was an ihre Stelle trat, steht in ADR-21: `auth-kobil` bietet nur die
Entsperrwege an, die es für dieses Credential wirklich gibt.

**Zweite erwogene Alternative**: Beim Widerruf einfach alle aktiven Methoden neu gegen ihr
`requires` prüfen. Genau das passiert — nur muss die Vorschau dafür mit dem Schreibvorgang übereinstimmen: Welche
Typen eine widerrufene Instanz mitnimmt, ermittelt `AccountService.claimedTypesOf` mit **derselben**
Abfrage und demselben `MethodModule`-Filter wie der Widerruf selbst. Eine Vorhersage, die vom
Schreibvorgang abweicht, wäre schlimmer als keine.

**Was das kostet:**
- `requires` bedeutet jetzt mehr als vorher. Jede bestehende Angabe erbt die neue Semantik —
  heute unkritisch, weil die einzige Angabe auf einen Anker (`EMAIL`) nur über den neuen,
  ausdrücklichen Widerrufs-Endpunkt verloren gehen kann, nie versehentlich. Der Ankertausch
  („anker-ersetzt") behauptet im selben Zug neu und löst deshalb nichts aus.
- Ein Attribut zurückzunehmen hat damit weite Folgen: Die Adresse nimmt das Passwort mit. Das
  ist gewollt und wird vorher geprüft — die Mindestniveau-Prüfung rechnet **mit** allem, was
  mitfällt, und die Ablehnung nennt es beim Namen.
- `AttributeType` trägt mit `PASSWORD_EXISTS` erstmals eine Aussage, die nichts über die **Person**
  sagt, sondern über die Credentials des Kontos. Bewusst dort und nicht in einem zweiten
  Mechanismus: Das Claim-Log verwaltet ohnehin genau die Lebensdauer, um die es hier geht.
