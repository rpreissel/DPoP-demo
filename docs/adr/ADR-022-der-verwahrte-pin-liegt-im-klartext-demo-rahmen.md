# ADR-22: Der verwahrte PIN liegt im Klartext — Demo-Rahmen, benannt statt verschwiegen

**Entscheidung** (**umgesetzt**): `auth_kobil.enrollment.pin` ist eine Klartextspalte. Ein Hash ist
ausgeschlossen, weil der Wert herausgegeben werden muss; verschlüsselt wird er nicht.

**Erwogene Alternative**: AES-256-GCM unter einem in `dpop.secrets` konfigurierten Schlüssel.
Für diese Demo verworfen: Sie schützt gegen eine gestohlene Kopie der Datenbank, aber nicht gegen
Zugriff auf den laufenden Anwendungsprozess, und der simulierte Anbieter (`kobil_mock`) hält denselben PIN ohnehin im
Klartext, so wie das echte KOBIL es tun müsste. Verschlüsselung auf nur einer der beiden Seiten
sähe nach Schutz aus, ohne einer zu sein.

**Zweite erwogene Alternative**: Den PIN pro Anmeldung neu setzen (KOBIL kann das) und danach
verwerfen. Reizvoll, weil dann nichts dauerhaft gespeichert bleibt. Es macht aber jede Anmeldung von der
Verwaltungsschnittstelle des Anbieters abhängig und öffnet ein Zeitfenster, in dem sich der Wechsel
des PINs und die Anmeldung über das SDK gegenseitig überholen können.

**Kosten**: Die H2-Konsole bleibt im Projekt bewusst offen, und ihr Kommentar zählt auf, was dort
lesbar ist. Diese Liste wächst um „jeder gültige KOBIL-PIN“. Vertretbar ist das nur, solange es dort so steht.
Denselben Fall gibt es im Projekt schon: `AccountKeycloakKeypair.privateKeyJwk` („Demo-only:
plaintext, not encrypted at rest“). Verwandt, aber nicht dasselbe:
`docs/ideen/verschluesselung-differenzierte-aufbewahrung.md` entwirft eine Verschlüsselung mit
eigenen Schlüsseln je Datensatz (Envelope Encryption) für das Claim-Log. Diesem Vorhaben greift diese
Entscheidung nicht vor.

Zusätzlich liegen während einer laufenden Einrichtung PIN **und** Entsperrgeheimnis im Klartext in
`auth_kobil.enroll_tool_session`. Das ist Absicht, damit ein Neuladen der Seite den Ablauf nicht
abbricht. Als Gegengewicht dient die gemeinsame Frist von 24 Stunden für Tool-Sessions (`tool-session.retention` in
`application.yml`, aufgeräumt über `AuthKobilRetentionJob`).

---
