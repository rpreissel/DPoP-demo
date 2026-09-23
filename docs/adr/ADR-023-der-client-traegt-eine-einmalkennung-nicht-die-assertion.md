# ADR-23: Der Client trägt eine Einmalkennung, nicht die Assertion

**Entscheidung** (**umgesetzt**): Bei `auth-kobil` läuft der Nachweis nicht durch den Client. Die
App erhält vom KOBIL-SDK nur ein One-Time-Password; die Geräte-Assertion samt Gerätekennung und
Risikosignalen löst das Backend selbst beim Anbieter ein.

**Erwogene Alternative**: Die Assertion (signiert) durch den Client weiterreichen und serverseitig
prüfen — das Muster von `auth_device`s `device-proof+jwt`. Funktioniert, verlangt aber ein
Vertrauensanker- und Signaturformat, das die öffentliche KOBIL-Dokumentation nicht nennt. Es hier
zu erfinden würde bedeuten, ein selbst gebautes Format als das echte auszugeben.

**Kosten und Gewinn**: Eine Server-zu-Server-Abhängigkeit im Anmeldepfad — ist der Anbieter nicht
erreichbar, ist das Verfahren nicht nutzbar. Dafür kann ein manipulierter Client hier nichts
behaupten: Er kann eine Kennung zurückhalten oder wiederholen, und beides endet in derselben
Antwort („Bestaetigung nicht erkannt"), weil eine Assertion genau einmal einlösbar ist.

Zwei Folgen, die im Ablauf sichtbar sind: Die Gerätekennung wird **bei KOBIL erfragt**, nie vom
Client übernommen — sie ist der Vergleichsanker jeder späteren Anmeldung. Und eine Risiko-Ablehnung
trägt einen **eigenen** Fehlergrund („Geraet als unsicher gemeldet"), weil das keine Verwechslung
des Nutzers ist, sondern eine Aussage über das Gerät; in einem „nicht erkannt" würde ein echter
Befund verschwinden. Bewusst in Kauf genommen: Diese Ablehnung belastet den Login-Throttle wie ein falsches
Passwort, ein gerootetes Telefon kann seinen Besitzer also aussperren.

---
