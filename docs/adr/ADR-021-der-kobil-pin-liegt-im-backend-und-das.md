# ADR-21: Der KOBIL-PIN liegt im Backend — und das Zugangsmittel zählt trotzdem

**Entscheidung** (**umgesetzt**): Beim Verfahren `kobil` wird der PIN nicht vom Nutzer vergeben und
nicht von ihm eingetippt, sondern vom Tool-Backend erzeugt, dort verwahrt und pro Anmeldung
freigegeben, nachdem der Client sich lokal entsperrt hat — per biometriegeschütztem Gerätegeheimnis
oder per Kontopasswort. Der Entsperrweg ist das `userVerification` dieses Verfahrens (`pin` bzw.
`biometric`), kein zweiter Nachweis; beide Wege erreichen `loa2`, genau wie bei `auth_device`.

**Keiner der beiden Wege ist Pflicht, und welche existieren, rechnet der Server aus.** Die
Biometrie entsteht nur bei Zustimmung (dann gibt es einen `unlock_secret_hash`, sonst NULL), das
Passwort nur, solange das Konto eines hält. `auth-kobil` nennt im `stepData` die tatsächlich
vorhandenen Wege (`unlockOptions`), statt beide anzubieten: Ein Weg, den es nicht gibt, hätte genau
einen möglichen Ausgang — einen Fehlversuch, der den Login-Throttle belastet. Das kostet etwas: Die Antwort
verrät, ob das Konto ein Passwort hat. Vertretbar, weil dieser Schritt nur für einen Aufrufer läuft,
dessen Schlüssel schon zu einem Credential dieses Kontos passt, und weil derselbe Aufrufer direkt
danach `activeMethods` sieht. Eine leere Liste ist möglich und wird auch so gesagt, statt verdeckt.

**Erwogene Alternative**: Den KOBIL-Standardweg beibehalten, also den Nutzer einen PIN vergeben und
eingeben lassen. Verworfen, weil das Verfahren hier gerade zeigen soll, wie ein Dienstleister für Gerätebindung
eingebunden wird, ohne dem Nutzer ein weiteres Geheimnis abzuverlangen.

**Zweite erwogene Alternative**: `docs/04-orchestrierung.md` Abschnitt 8 wörtlich nehmen („nur
Faktoren melden, die dem Server nachweisbar sind") und nur `{possession}` melden; der
Biometrie-Weg würde dann bei `loa1` landen. Verworfen, obwohl sie strenger und in einem Punkt richtiger ist:
Wie entsperrt wurde, kann kein Server sehen. Eine strengere Regel hätte aber zur Folge, dass dieselbe Geste
in zwei Verfahren unterschiedlich viel kostet, ohne dass der Nutzer den Grund sieht — und dass
`auth_device`, das `inherence` von Anfang an aus derselben Client-Angabe meldet, zur unerklärten
Ausnahme würde.

**Was das kostet**: Die Ausnahme von der „nur nachweisbare Faktoren"-Regel gilt damit für
zwei Verfahren. Sie steht deshalb dort als **Ausnahme** notiert, nicht als zwei Einzelfälle. Was
bei `kobil` dagegen stärker belegt ist als überall sonst: Der Besitzfaktor beruht auf einer
Assertion, die das Backend selbst beim Anbieter einlöst, nicht auf einer Client-Signatur (ADR-23).

Zwei Dinge, die aus dieser Entscheidung folgen und im Code als Typ stehen, nicht als Kommentar:
`KobilUnlockCredential` ist ein `sealed interface` (Gerätegeheimnis **oder** Passwort — beides oder
nichts ist nicht konstruierbar) und trägt seine Faktorart selbst. Und das Kontopasswort meldet
`pin`, nie `password`: Ein amr-Eintrag dieses Namens würde dem Lauf über
`findActiveMethod(accountId, "password")` den Enrollment-Datensatz der echten Passwortmethode
anhängen und sie damit doppelt zählen.

---
