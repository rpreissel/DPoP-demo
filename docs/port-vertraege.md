# Port-Verträge der Fremdsysteme

Was ein **echtes** Fremdsystem zusagen muss, damit der Kern ihm so vertrauen darf, wie er es heute
tut ([ADR-35](adr/ADR-035-betriebsanspruch-backend-kern-produktionsreif.md), Review 2026-09,
Fahrplan-Schritt 27). In dieser Instanz sind alle simuliert. Die Simulationen bauen die Sicherheit
eines echten Systems nicht nach – der Vertrag hier sagt, was beim Anschluss des echten Systems zu
prüfen ist. Ein Verfahren, dessen Gegenstelle simuliert ist und dessen Niveau allein an ihr hängt,
ist `demoOnly` und außerhalb des Demomodus aus ([ADR-36](adr/ADR-036-niveaus-und-ihre-nachweise.md)).

Für jeden Vertrag gilt: Der Kern entscheidet nur über das, was die Antwort des Systems zusagt. Er
leitet nichts aus Client-Angaben ab, die das System hätte prüfen müssen.

---

## Personenverzeichnis (`PersonDirectory`, `Freischaltcodes`)

Genutzt von `ident-fsc`, `ident-kvnr` und dem Abgleich jeder Identifizierung. **Nicht** `demoOnly`:
Mit `demo.mode=false` ist es der einzige Weg zu einer Identifizierung, also ist dieser Vertrag der
wichtigste.

- **Suche nur über Kennungen.** KVNR und Partnernummer führen zur Partnernummer; Stammdaten gehen nie
  über den Port, nur die Antwort auf „passen diese Angaben“ (`matchesMasterData`,
  `matchesPersonalDetails`). Ausnahmen, ausdrücklich: der Anzeigename und die Versicherungsnummer (ADR-34).
- **Namensvergleich in Ausweisform** (MRZ): Groß-/Kleinschreibung, Umlautschreibung und Diakritika
  zählen nicht. Ein echtes System muss denselben Vergleich liefern, sonst scheitern echte Personen.
- **Freischaltcode** (ADR-31): nur vom Verzeichnis ausgegeben, per Post an die hinterlegte Anschrift;
  gespeichert nie im Klartext (Hash mit Pepper), mit Ablauf; widerrufbar. Bis zum Ablauf bewusst
  wiederverwendbar (Re-Identifizierung, ADR-31). Die Länge und damit die Ratesicherheit ist Sache des
  Systems; der Kern drosselt Fehlversuche je Person (`IdentThrottleService`).
- **Änderungen werden gemeldet** (ADR-34): Das System meldet jede Änderung an einer Person; der Kern
  verarbeitet sie einspurig (`PersonChangeListener`). Ein echtes System muss mindestens einmal
  zustellen und Reihenfolge je Person wahren.

## KOBIL (`kobil_mock.KobilSsms`) – `demoOnly`

Genutzt von `enroll-kobil`, `auth-kobil` (loa2).

- **Das Ergebnis kommt vom Server.** `verifyOtp` löst ein Einmalpasswort beim KOBIL-Server ein; nur
  dessen Antwort (Gerät, Risikosignale) zählt, nie eine Angabe der App.
- **Einmaligkeit:** Ein OTP gilt genau einmal und kurz; ein zweites Einlösen liefert nichts.
- **Nutzerverifikation am Gerät:** PIN bzw. Biometrie prüft KOBIL auf dem Gerät und bestätigt das
  signiert. Ohne diese Zusage wäre `auth-kobil` kein zweiter Faktor.
- **Risikosignale** (gerootetes Gerät usw.) werden gemeldet; der Kern lehnt dann ab.
- **Aktivierungsgeheimnisse** sind kurzlebig und werden nicht erneut ausgeliefert (Review, niedrige
  Befunde: heute 24 h im Klartext in der ToolSession – beim Anschluss zu kürzen).

## Nect (`nect_mock.NectIdent`) – `demoOnly`

Genutzt von `ident-nect` (loa3).

- **Das Ergebnis holt der Server ab** (`redeem` über die Fall-ID), nie aus dem Rücksprung des
  Browsers. Der Rücksprung sagt nur „fertig“, nicht „wer“.
- **Einmal einlösbar:** Ein Fall liefert sein Ergebnis genau einmal.
- **Bindung an den Vorgang:** Der Fall gehört zu genau diesem Ablauf (Callback-URI); ein fremder
  Fall wird abgelehnt.
- **Nur angefragte Attribute** werden geliefert; die Echtheit von Dokument und Person (Selfie gegen
  Passbild, Ablauf des Passes) prüft Nect und meldet das Ergebnis.

## eID-Server (in `id_eid` simuliert) – `demoOnly`

Genutzt von `ident-eid` (loa3).

- **Das Ergebnis kommt serverseitig vom eID-Server**, nach dessen eigener Prüfung der Karte und der
  PIN; nie aus Angaben des Clients. Heute simuliert `id_eid` die Kartenlesung selbst.
- **`restricted_id`** (Sperrmerkmal je Dienstanbieter) ist der Anker ([ADR-19](adr/ADR-019-aufloesung-nur-ueber-anker-die-eid-restricted-id.md)):
  stabil je Karte und Anbieter.
- **Keine Personenkennung auf der Karte:** Der Abgleich mit dem Personenverzeichnis läuft über die
  Ausweisdaten (`matchesMasterData`), nicht über eine Nummer.

## Zustellung von TAN und Code (`sms_mock`, `mail_mock`)

Genutzt von `enroll-sms`, `auth-sms`, `confirm-email`, `auth-email` und den `-lookup`-Varianten.

- **Zustellung nur an die hinterlegte Adresse**, ohne Rückkanal des Inhalts in Logs (Review M-11:
  der Kern schreibt TAN und Code nie auf die Konsole).
- **Drosselung** je Empfänger liegt im Kern (`SendThrottleService`); ein echter Dienst darf zusätzlich
  drosseln, muss aber einen abgelehnten Versand melden.
- Diese Verfahren sind nicht `demoOnly`: Was sie beweisen (Besitz der Nummer bzw. des Postfachs),
  prüft der Kern selbst über den zugestellten Code.
