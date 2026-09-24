# ADR-22: Demo-Geheimnisse liegen im Klartext — benannt statt verschwiegen

**Status:** umgesetzt.

## Entscheidung

Einige Geheimnisse speichert die Demo bewusst im Klartext. Das ist ein Kompromiss für die Demo, und
er steht hier an **einer** Stelle, statt in jedem betroffenen ADR neu begründet zu werden:

| Was | Wo | Warum nicht gehasht |
|---|---|---|
| verwahrter KOBIL-PIN ([ADR-21](ADR-021-der-kobil-pin-liegt-im-backend-und-das.md)) | `auth_kobil.enrollment.pin` | muss für jede Anmeldung herausgegeben werden |
| PIN und Entsperrgeheimnis während einer KOBIL-Einrichtung | `auth_kobil.enroll_tool_session` | ein Neuladen der Seite soll den Ablauf nicht abbrechen |
| Freischaltcode im Brief ([ADR-31](ADR-031-freischaltcode-liegt-im-fremdsystem.md)) | `ext_personenverzeichnis.brief.code` | den Klartext gibt es auch in der echten Welt, auf Papier; geprüft wird nur gegen den Hash in `freischaltcode` |
| privater Schlüssel je Konto für den Token-Grant ([ADR-9](ADR-009-profilabhaengiges-token-retrieval-account-keypair-custom-oauth2-grant.md)) | `orchestrator.keycloak_keypair.private_key_jwk` | muss zum Signieren gelesen werden |
| Signaturschlüssel des Orchestrators ([ADR-25](ADR-025-die-keycloak-konfiguration-steht-im-realm-nicht-in.md)) | `orchestrator.node_signing_key` | wie oben |
| Signaturschlüssel der Keycloak-Erweiterung (ADR-25) | Wert `peerAuthSigningKeyJwk` der Komponente `orchestrator`, in der Datenbank von Keycloak | wie oben |

Verschlüsselt wird nichts davon.

## Begründung

Verschlüsselung (etwa AES-256-GCM unter einem Schlüssel aus `dpop.secrets`) schützt gegen eine
gestohlene Kopie der Datenbank, aber nicht gegen Zugriff auf den laufenden Prozess, der den Schlüssel
ja kennen muss. Beim KOBIL-PIN hält außerdem der simulierte Anbieter (`kobil_mock`) denselben Wert
ohnehin im Klartext, so wie das echte KOBIL es tun müsste. Verschlüsselung auf nur einer Seite sähe
nach Schutz aus, ohne einer zu sein. Für eine Demo wiegt das den Aufwand für Schlüsselverwaltung nicht
auf.

**Erwogene Alternative beim PIN:** ihn pro Anmeldung neu setzen (KOBIL kann das) und danach verwerfen.
Dann bliebe nichts dauerhaft gespeichert. Es macht aber jede Anmeldung von der
Verwaltungsschnittstelle des Anbieters abhängig und öffnet ein Zeitfenster, in dem sich der Wechsel
des PINs und die Anmeldung über das SDK gegenseitig überholen können.

## Folgen

- Vertretbar ist das nur, solange es sichtbar bleibt. Die H2-Konsole ist im Projekt bewusst an; ihr
  Kommentar in `application.yml` zählt auf, was dort lesbar wäre, wenn man sie über
  `web-allow-others` öffnete. Die Klartextfelder oben gehören auf diese Liste.
- Die Einrichtungsdaten von KOBIL liegen nur so lange wie jede Tool-Session: höchstens 24 Stunden
  (`tool-session.retention`, aufgeräumt über `AuthKobilRetentionJob`).
- Ein echter Betrieb bräuchte für jede Zeile der Tabelle eine eigene Lösung (Schlüsselspeicher,
  HSM, verschlüsselte Spalten). Eine Verschlüsselung mit eigenen Schlüsseln je Datensatz für das
  Claim-Log entwirft [die Idee zur Umschlagverschlüsselung](../ideen/verschluesselung-differenzierte-aufbewahrung.md);
  dieser Entscheidung greift sie nicht vor.

## Geschichte

Ursprünglich betraf dieser ADR nur den verwahrten KOBIL-PIN. Dieselbe Abwägung stand danach zusätzlich
in ADR-9, ADR-25 und ADR-31. Sie ist jetzt hier zusammengefasst; die anderen ADRs verweisen nur noch
hierher.
