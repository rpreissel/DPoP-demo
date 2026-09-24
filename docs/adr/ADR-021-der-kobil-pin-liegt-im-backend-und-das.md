# ADR-21: Der KOBIL-PIN liegt im Backend — und das Zugangsmittel zählt trotzdem

**Entscheidung** (**umgesetzt**): Beim Verfahren `kobil` wird der PIN nicht vom Nutzer vergeben und
nicht von ihm eingetippt, sondern vom Backend des Tools erzeugt, dort verwahrt und für jede
Anmeldung freigegeben, nachdem sich der Client auf dem Gerät entsperrt hat: mit einem
Gerätegeheimnis, das durch Biometrie geschützt ist, oder mit dem Passwort des Kontos. Wie entsperrt
wurde, ist das `userVerification` dieses Verfahrens (`pin` bzw. `biometric`) und kein zweiter Nachweis.
Beide Wege erreichen `loa2`, genau wie bei `auth_device`.

**Keiner der beiden Wege ist Pflicht, und welche existieren, rechnet der Server aus.** Die
Biometrie entsteht nur bei Zustimmung (dann gibt es einen `unlock_secret_hash`, sonst NULL), das
Passwort nur, solange das Konto eines hält. `auth-kobil` nennt im `stepData` die tatsächlich
vorhandenen Wege (`unlockOptions`), statt beide anzubieten: Ein Weg, den es nicht gibt, könnte nur
zu einem Fehlversuch führen, und der würde auf den Zähler für fehlgeschlagene Anmeldungen gehen. Das kostet etwas: Die Antwort
verrät, ob das Konto ein Passwort hat. Vertretbar, weil dieser Schritt nur für einen Aufrufer läuft,
dessen Schlüssel schon zu einem Credential dieses Kontos passt, und weil derselbe Aufrufer direkt
danach `activeMethods` sieht. Eine leere Liste ist möglich und wird auch offen so gemeldet.

**Erwogene Alternative**: Den KOBIL-Standardweg beibehalten, also den Nutzer einen PIN vergeben und
eingeben lassen. Verworfen, weil das Verfahren hier gerade zeigen soll, wie man einen Dienstleister für die Bindung an
ein Gerät einbindet, ohne dem Nutzer ein weiteres Geheimnis abzuverlangen.

**Zweite erwogene Alternative**: `docs/04-orchestrierung.md` Abschnitt 8 wörtlich nehmen („nur
Faktoren melden, die dem Server nachweisbar sind") und nur `{possession}` melden; der
Biometrie-Weg würde dann bei `loa1` landen. Verworfen, obwohl sie strenger und in einem Punkt richtiger ist:
Wie entsperrt wurde, kann kein Server sehen. Die strengere Regel hätte aber zwei Folgen: Dieselbe
Handlung wäre in zwei Verfahren unterschiedlich viel wert, ohne dass der Nutzer den Grund sieht. Und
`auth_device`, das `inherence` von Anfang an aus derselben Angabe des Clients meldet, würde zur
unerklärten Ausnahme.

**Was das kostet**: Die Ausnahme von der Regel „nur nachweisbare Faktoren“ gilt damit für zwei
Verfahren. Sie steht deshalb dort als **Ausnahme** notiert, nicht als zwei Einzelfälle. Was
bei `kobil` dagegen stärker belegt ist als überall sonst: Der Besitzfaktor beruht auf einer
Assertion, die das Backend selbst beim Anbieter einlöst, und nicht auf einer Signatur des Clients
(ADR-23).

Zwei Dinge, die aus dieser Entscheidung folgen und im Code als Typ stehen, nicht als Kommentar:
`KobilUnlockCredential` ist ein `sealed interface` (Gerätegeheimnis **oder** Passwort — beides oder
nichts ist nicht konstruierbar) und trägt seine Faktorart selbst. Und das Passwort des Kontos wird als
`pin` gemeldet, nie als `password`: Ein `amr`-Eintrag mit diesem Namen würde über
`findActiveMethod(accountId, "password")` den Datensatz des echten Passwortverfahrens an diesen
Durchlauf hängen und das Passwort damit doppelt zählen.

---
