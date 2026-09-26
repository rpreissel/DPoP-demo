# ADR-23: Der Client trägt eine Einmalkennung, nicht die Assertion

**Status:** Aufgegangen in [ADR-21](../../adr/ADR-021-der-kobil-pin-liegt-im-backend-und-das.md).

Die Entscheidung, dass bei `auth-kobil` das Backend die Assertion selbst beim Anbieter einlöst und der
Client nur ein Einmalpasswort trägt, steht jetzt als Abschnitt 2 in ADR-21 „KOBIL-Anbindung“,
zusammen mit der Entscheidung zum verwahrten PIN.
