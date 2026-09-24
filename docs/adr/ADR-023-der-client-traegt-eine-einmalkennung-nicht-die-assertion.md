# ADR-23: Der Client trägt eine Einmalkennung, nicht die Assertion

**Entscheidung** (**umgesetzt**): Bei `auth-kobil` läuft der Nachweis nicht durch den Client. Die
App erhält vom KOBIL-SDK nur ein Einmalpasswort. Die Assertion über das Gerät samt Gerätekennung und
Risikosignalen löst das Backend selbst beim Anbieter ein.

**Erwogene Alternative**: Die Assertion (signiert) durch den Client weiterreichen und serverseitig
prüfen, nach dem Muster von `device-proof+jwt` bei `auth_device`. Das funktioniert, verlangt aber
einen Vertrauensanker und ein Signaturformat, die die öffentliche Dokumentation von KOBIL nicht nennt. Es hier
zu erfinden würde bedeuten, ein selbst gebautes Format als das echte auszugeben.

**Kosten und Gewinn**: Bei der Anmeldung hängt unser Server vom Server des Anbieters ab. Ist der
Anbieter nicht erreichbar, lässt sich das Verfahren nicht nutzen. Dafür kann ein manipulierter Client hier nichts
behaupten: Er kann eine Kennung zurückhalten oder wiederholen, und beides endet in derselben
Antwort („Bestaetigung nicht erkannt"), weil eine Assertion genau einmal einlösbar ist.

Zwei Folgen, die im Ablauf sichtbar sind: Die Gerätekennung wird **bei KOBIL erfragt** und nie vom
Client übernommen, denn mit ihr wird jede spätere Anmeldung verglichen. Und eine Ablehnung wegen
eines Risikos hat einen **eigenen** Fehlergrund („Geraet als unsicher gemeldet"), weil das keine Verwechslung
des Nutzers ist, sondern eine Aussage über das Gerät; in einem „nicht erkannt" würde ein echter
Befund verschwinden. Bewusst in Kauf genommen: Diese Ablehnung zählt beim Zähler für fehlgeschlagene Anmeldungen wie
ein falsches Passwort. Ein manipuliertes (gerootetes) Telefon kann seinen Besitzer also aussperren.

---
