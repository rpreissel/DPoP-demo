# ADR-21: KOBIL-Anbindung — PIN im Backend, Nachweis über eine Einmalkennung

**Status:** umgesetzt.

Das Verfahren `kobil` zeigt, wie man einen Dienstleister für die Bindung an ein Gerät einbindet, ohne
dem Nutzer ein weiteres Geheimnis abzuverlangen. Dafür gelten zwei Entscheidungen.

## 1. Der PIN liegt im Backend, und das Zugangsmittel zählt trotzdem

**Entscheidung.** Der KOBIL-PIN wird nicht vom Nutzer vergeben und nicht eingetippt. Das Backend des
Tools erzeugt ihn, verwahrt ihn und gibt ihn für jede Anmeldung frei, nachdem sich der Client auf dem
Gerät entsperrt hat: entweder mit einem durch Biometrie geschützten Gerätegeheimnis oder mit dem
Passwort des Kontos. Wie entsperrt wurde, ist das `userVerification` des Verfahrens (`biometric` bzw.
`pin`), kein zweiter Nachweis. Beide Wege erreichen `loa2`, genau wie `auth-device`.

**Welche Wege es gibt, rechnet der Server aus.** Die Biometrie entsteht nur bei Zustimmung (dann gibt
es einen `unlock_secret_hash`), das Passwort nur, solange das Konto eines hat. `auth-kobil` nennt im
`stepData` die tatsächlich vorhandenen Wege (`unlockOptions`). Ein Weg, den es nicht gibt, könnte nur
zu einem Fehlversuch führen, der auf den Zähler für fehlgeschlagene Anmeldungen ginge. Dafür verrät
die Antwort, ob das Konto ein Passwort hat. Das ist vertretbar: Der Schritt läuft nur für einen
Aufrufer, dessen Schlüssel schon zu einem Credential dieses Kontos passt, und derselbe Aufrufer sieht
direkt danach `activeMethods`.

Im Code steht das als Typ, nicht als Kommentar: `KobilUnlockCredential` ist ein `sealed interface`
(Gerätegeheimnis **oder** Passwort) und trägt seinen Faktortyp selbst. Das Passwort des Kontos wird
als `pin` gemeldet, nie als `password`: Ein `amr`-Eintrag `password` würde über
`findActiveMethod(accountId, "password")` den Datensatz des echten Passwortverfahrens an diesen
Durchlauf hängen und das Passwort doppelt zählen.

Dass der PIN dafür im Klartext verwahrt wird, begründet
[ADR-22](ADR-022-der-verwahrte-pin-liegt-im-klartext-demo-rahmen.md).

**Erwogene Alternativen.**

- Den KOBIL-Standardweg beibehalten, also der Nutzer vergibt und tippt einen PIN. Verworfen, weil
  genau das Ziel war, kein weiteres Geheimnis zu verlangen.
- Nur `{possession}` melden, weil der Server nicht sehen kann, wie entsperrt wurde
  ([Orchestrierung](../04-orchestrierung.md), Abschnitt 8: „nur Faktoren melden, die dem Server
  nachweisbar sind“). Das ist strenger und in einem Punkt richtiger, hätte den Biometrie-Weg aber auf
  `loa1` gesetzt. Dieselbe Handlung wäre dann in zwei Verfahren unterschiedlich viel wert, und
  `auth-device`, das `inherence` von Anfang an aus derselben Angabe des Clients meldet, würde zur
  unerklärten Ausnahme.

**Folge.** Die Ausnahme von „nur nachweisbare Faktoren“ gilt für zwei Verfahren (`auth-device` und
`auth-kobil`) und steht in der Orchestrierung als eine benannte Ausnahme.

## 2. Der Client trägt eine Einmalkennung, nicht die Assertion

**Entscheidung.** Bei `auth-kobil` läuft der Nachweis nicht durch den Client. Die App erhält vom
KOBIL-SDK nur ein Einmalpasswort. Die Assertion über das Gerät samt Gerätekennung und Risikosignalen
löst das Backend selbst beim Anbieter ein. Der Besitzfaktor ist damit stärker belegt als bei jedem
anderen Verfahren: Er beruht auf einer Assertion, die unser Backend einlöst, nicht auf einer Signatur
des Clients.

**Erwogene Alternative.** Die signierte Assertion durch den Client weiterreichen und im Server prüfen,
nach dem Muster von `device-proof+jwt` bei `auth-device`. Das verlangt aber einen Vertrauensanker und
ein Signaturformat, die die öffentliche Dokumentation von KOBIL nicht nennt. Ein selbst erfundenes
Format würde sich als das echte ausgeben.

**Folgen.**

- Bei der Anmeldung hängt unser Server vom Server des Anbieters ab. Ist er nicht erreichbar, lässt
  sich das Verfahren nicht nutzen.
- Ein manipulierter Client kann nichts behaupten. Er kann eine Kennung nur zurückhalten oder
  wiederholen, und beides endet in derselben Antwort („Bestätigung nicht erkannt“), weil eine Assertion
  genau einmal einlösbar ist.
- Die Gerätekennung wird **bei KOBIL erfragt**, nie vom Client übernommen, denn mit ihr wird jede
  spätere Anmeldung verglichen.
- Eine Ablehnung wegen eines Risikos hat einen **eigenen** Fehlergrund („Gerät als unsicher
  gemeldet“), weil sie eine Aussage über das Gerät ist und kein Versehen des Nutzers. Bewusst in Kauf
  genommen: Sie zählt beim Zähler für fehlgeschlagene Anmeldungen wie ein falsches Passwort. Ein
  manipuliertes (gerootetes) Telefon kann seinen Besitzer also aussperren.

## Geschichte

Die zweite Entscheidung stand ursprünglich als eigene
[ADR-23](../archiv/adr/ADR-023-der-client-traegt-eine-einmalkennung-nicht-die-assertion.md). Beide beschreiben
dieselbe Anbindung und sind deshalb hier zusammengeführt.
