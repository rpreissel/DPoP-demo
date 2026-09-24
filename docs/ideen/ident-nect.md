# Idee: Identifikation über Nect (`ident-nect`) – was noch offen ist

Status: **Demo umgesetzt**, nur im App-Kanal (siehe [03-tool-architektur.md](../03-tool-architektur.md),
Abschnitt „Was `ident-nect` von Nect bekommt“, und [06-ablaeufe.md](../06-ablaeufe.md)). Offen sind
der Web-Kanal (`DPoP-demo-p6rl`), die echte Anbindung (`DPoP-demo-v033`) und der Anker für den
Reisepass (`DPoP-demo-9cfw`). Dieses Dokument enthält nur noch diese offenen Teile; die ausführliche
Herleitung steht in der Git-Historie dieser Datei.

Kurz zum umgesetzten Stand:

- `id_nect` (Tool) ruft direkt die Klasse `nect_mock.NectIdent` (`createCase`/`redeem`/`caseView`),
  wie `auth_kobil → KobilSsms` (ADR-31). `nect_mock` hängt nur an `texts` (ADR-33).
- `ident-nect` bestätigt, was auf dem Dokument steht, und findet keine Person. Die Zuordnung zum
  Personenverzeichnis folgt wie nach `ident-eid` über `ident-kvnr`.
- `amr` je Verfahren: `nect-eid`, `nect-epass`, `nect-eudi`; Niveau `loa3`, `loa2` bzw. `loa3`.
- Beim Online-Ausweis liefert Nect das Pseudonym der Karte. Es ist je Diensteanbieter verschieden
  (§ 18 PAuswG), bei Nect also **Nects eigenes, nicht dasselbe wie bei `ident-eid`**. Deshalb bekommt es
  einen eigenen Anker (`NECT_RESTRICTED_ID`, Issue `DPoP-demo-brq5`): Dieselbe Karte wird über Nect
  und über den direkten Online-Ausweis nicht als dieselbe erkannt – genau wie in der Wirklichkeit.

---

## 1) Web-Kanal

Im Web-Kanal zeigt die Keycloak-Erweiterung die Schritte der Tools an. Für `ident-nect` gibt es dort
keinen Renderer, deshalb bietet der Web-Kanal das Verfahren nicht an; eine Sperre im Backend ist
nicht nötig, weil der Client selbst angibt, was er kann.

Schwierig ist die Weiterleitung: Die Anmeldeseite von Keycloak muss zu Nect wechseln und danach in
**dieselbe** Anmeldung zurückkehren (`login-actions` von Keycloak, an die Anmeldesitzung gebunden).
Vorschlag: ein `WebToolRenderer`, der eine Action-URL von Keycloak als `callback_uri` an Nect
mitgibt und beim Rücksprung den Vorgang einlöst.

Offene Frage: Wann kommt das, und wie genau läuft der Rücksprung aus Keycloak heraus?

---

## 2) Echte Anbindung: über ext-ident oder direkt an Nect

Für die echte Anbindung muss aus `NectIdent` zuerst eine Schnittstelle werden, mit einer simulierten
und einer echten Umsetzung (per Profil gewählt).

| | A: ext-ident als Dienst anbinden | B: Logik von ext-ident ins Modul holen, Nect direkt |
|---|---|---|
| Muster | Abhängigkeit zu einem Fremdsystem wie `kobil_mock` (ADR-31) | ebenso; Nect ist das Fremdsystem |
| Sicherheit zwischen den Diensten | Order-JWT, Confirmation-JWT, PKCE, JWKS und eine Liste erlaubter Aussteller nötig | entfällt (etwa 40 % des Codes von ext-ident) |
| Betrieb | zwei Systeme, ein Netzwerkweg mehr | eine Installation, Nect als einzige externe Stelle |
| Rückmeldung | Nect → ext-ident | Nect → dieses System (`PUT /callbacks/nect/{caseId}`, ohne Anmeldung; geschützt über eine nicht vorhersagbare `caseId` und eine Prüfung des Zustands) |
| Abgleich mit TKeasy | in ext-ident | entfällt; Zuordnung über `ident-kvnr` |
| Aufwand | etwa 3–5 Tage | etwa 7–9 Tage |
| Andere Nutzer von ext-ident | bleiben unberührt | müssten eine eigene Anbindung bauen |

Empfehlung: **A**, solange ext-ident noch andere Nutzer hat; **B**, wenn nur dieses System Nect nutzt.

Bei A gilt weiter die Option „PKCE auf dem Server“: Das Backend erzeugt `code_verifier` und
`code_challenge`, reicht die Order selbst ein und löst sie selbst ein. Das Frontend sieht nur die
`redirect_uri` und den Rücksprung. Offen sind dort die Liste erlaubter Aussteller und der Wert von
`process` bei ext-ident, die Audience der Confirmation und das nur einmalige Einlösen.

Offene Frage: A oder B? Das hängt davon ab, wer ext-ident sonst noch nutzt.

---

## 3) Anker für den Reisepass

Beim Reisepass fragt `ident-nect` schon die Dokumentnummer an (`DOCUMENT_ID`), legt daraus aber keinen
Anker an. Ein Anker aus **Dokumentnummer + Ausstellerstaat** würde ein Konto beim nächsten Lauf mit
demselben Pass wiedererkennen. Er wechselt mit jedem neuen Pass, wie das Pseudonym bei einer neuen
Karte (ADR-19). Nötig wären ein eigener `AttributeType` mit Normalisierung, eine Ankerregel in
`AttributeRules` (Erstbinden und Ersetzen ab `loa2`, wie beim Pass selbst) und die Übernahme im
Handler. Die EUDI-Wallet bekommt keinen Anker: Die PID trägt kein Pseudonym gegenüber der
vertrauenden Stelle.
