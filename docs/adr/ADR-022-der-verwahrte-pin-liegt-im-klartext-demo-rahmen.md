# ADR-22: Der verwahrte PIN liegt im Klartext — Demo-Rahmen, benannt statt verschwiegen

**Entscheidung** (**umgesetzt**): `auth_kobil.enrollment.pin` ist eine Klartextspalte. Ein Hash ist
ausgeschlossen, weil der Wert herausgegeben werden muss; verschlüsselt wird er nicht.

**Erwogene Alternative**: AES-256-GCM unter einem in `dpop.secrets` konfigurierten Schlüssel.
Verworfen für diese Demo: Sie schützt gegen einen gestohlenen Datenbankstand, nicht gegen Zugriff
auf den Anwendungsprozess — und der simulierte Anbieter (`kobil_mock`) hält denselben PIN ohnehin im
Klartext, so wie das echte KOBIL es tun müsste. Verschlüsselung auf nur einer der beiden Seiten
sähe nach Schutz aus, ohne einer zu sein.

**Zweite erwogene Alternative**: Den PIN pro Anmeldung neu setzen (KOBIL kann das) und danach
verwerfen. Reizvoll, weil dann nichts dauerhaft gespeichert bleibt. Es hängt aber jeden Login an den
Management-Pfad des Anbieters und öffnet ein Zeitfenster, in dem PIN-Wechsel und SDK-Login
einander überholen können.

**Kosten**: Die H2-Konsole bleibt im Projekt bewusst offen, und ihr Kommentar zählt auf, was dort
lesbar ist. Diese Liste wächst um „jeder lebende KOBIL-PIN". Vertretbar nur, solange es so
dasteht. Denselben Fall gibt es im Projekt schon: `AccountKeycloakKeypair.privateKeyJwk` („Demo-only:
plaintext, not encrypted at rest"). Verwandt, aber nicht dasselbe:
`docs/ideen/verschluesselung-differenzierte-aufbewahrung.md` entwirft Envelope Encryption für das
Claim-Log — einem Vorhaben, dem hier nicht vorgegriffen wird.

Zusätzlich liegen während einer laufenden Einrichtung PIN **und** Unlock-Secret im Klartext in
`auth_kobil.enroll_tool_session` — dort absichtlich, damit ein Neuladen der Seite den Ablauf nicht abbricht,
und mit der zentralen 24-Stunden-Frist für Tool-Sessions (`tool-session.retention` in
`application.yml`, abgeräumt über `AuthKobilRetentionJob`) als Gegengewicht.

---
