# Architekturentscheidungen

Hier stehen die großen Entscheidungen dieses Projekts, jeweils mit der ernsthaft erwogenen
Alternative und dem Preis der gewählten Lösung. Wie die Lösung *aussieht*, beschreiben die
verlinkten Kapitel; hier steht nur das *Warum*.

---

## Die Entscheidungen

Jede Entscheidung hat eine eigene Datei unter [`adr/`](adr/). Früher standen alle in dieser einen
Datei (66 KB). Wer eine Entscheidung nachlesen wollte, musste alle laden, und jede Änderung erschien
als Diff mitten im Fließtext.

| ADR | Entscheidung |
|---|---|
| [ADR-1](adr/ADR-001-ein-controller-je-tool-kein-generischer-dispatcher.md) | Ein Controller je Tool, kein generischer Dispatcher |
| [ADR-2](adr/ADR-002-zustand-statt-vererbung-bei-authjourney.md) | Zustand statt Vererbung bei `AuthJourney` |
| [ADR-3](adr/ADR-003-channelsession-bewusst-kurzlebig-geraete-identitaet-in-deviceaccountlink.md) | `ChannelSession` bewusst kurzlebig, Geräte-Identität in `DeviceAccountLink` |
| [ADR-4](archiv/adr/ADR-004-flyway-neubaseline-statt-migration-des-altcodes.md) | Neue Flyway-Ausgangsbasis statt Migration des Altcodes *(abgelöst durch ADR-14 und ADR-16)* |
| [ADR-5](adr/ADR-005-drei-obergrenzen-fuer-das-sicherheitsniveau.md) | Zwei Obergrenzen für das Sicherheitsniveau |
| [ADR-6](adr/ADR-006-next-als-reine-adresse-feste-routing-tabelle-statt.md) | `next` als reine Adresse, feste Routing-Tabelle statt HATEOAS |
| [ADR-7](adr/ADR-007-web-kanal-ohne-mtls-signierte-request-assertion-statt.md) | Web-Kanal ohne mTLS, signierte Request-Assertion statt Client-Zertifikat |
| [ADR-8](adr/ADR-008-keycloak-fuehrt-seine-eigenen-nativen-schritte-selbst-statt.md) | Keycloak führt seine eigenen nativen Schritte selbst, statt alles zu delegieren oder über Identity-Brokering zu gehen |
| [ADR-9](adr/ADR-009-profilabhaengiges-token-retrieval-account-keypair-custom-oauth2-grant.md) | Profilabhängiger Token-Abruf — eigener OAuth2-Grant, den nur der Orchestrator aufrufen darf |
| [ADR-10](adr/ADR-010-interessent-ist-konto-zustand-kein-eigener-authintent.md) | Interessent ist Konto-Zustand, kein eigener AuthIntent |
| [ADR-11](adr/ADR-011-kontouebergreifender-person-id-konflikt-ist-abweisung-merge-nie.md) | Ein Anker, der schon einem anderen Konto gehört, wird abgewiesen; Konten werden nie automatisch zusammengeführt |
| [ADR-12](adr/ADR-012-ein-widerruf-ist-eine-eigene-zeile-mit-eigenem.md) | Ein Widerruf ist eine eigene Zeile mit eigenem Vertrauensanker |
| [ADR-13](archiv/adr/ADR-013-account-domaenentypen-ueber-eigene-attributeconverter-nicht-enumerated.md) | Account-Domänentypen über eigene `AttributeConverter`, nicht `@Enumerated` *(aufgegangen in `db/migration/KONVENTIONEN.md`)* |
| [ADR-14](adr/ADR-014-schema-zusammengefuehrt-das-konto-als-sperrpunkt-eine-wahrheit.md) | Das Konto als gemeinsame Sperre, jeder Fakt an genau einer Stelle |
| [ADR-15](adr/ADR-015-nachweise-und-ausgestellte-tokens-in-getrennten-tabellen.md) | Nachweise und ausgestellte Tokens in getrennten Tabellen |
| [ADR-16](adr/ADR-016-ein-datenbankschema-je-modul-statt-namenspraefix.md) | Ein Schema und ein Migrationsordner je Modul |
| [ADR-17](adr/ADR-017-adresse-bestaetigen-und-e-mail-login-einrichten-sind.md) | Adresse bestätigen und E-Mail-Login einrichten sind zwei Schritte |
| [ADR-18](adr/ADR-018-bestaetigen-und-zuordnen-sind-zwei-akte.md) | Bestätigen und Zuordnen sind zwei Schritte |
| [ADR-19](adr/ADR-019-aufloesung-nur-ueber-anker-die-eid-restricted-id.md) | Konten werden nur über Anker gefunden — auch die `restricted_id` der eID ist einer |
| [ADR-20](adr/ADR-020-ein-vorlaeufiges-konto-geht-im-gefundenen-auf-statt.md) | Ein vorläufiges Konto geht im gefundenen auf, statt den Lauf abzuweisen |
| [ADR-21](adr/ADR-021-der-kobil-pin-liegt-im-backend-und-das.md) | KOBIL-Anbindung — PIN im Backend, Nachweis über eine Einmalkennung |
| [ADR-22](adr/ADR-022-der-verwahrte-pin-liegt-im-klartext-demo-rahmen.md) | Demo-Geheimnisse liegen im Klartext — benannt statt verschwiegen |
| [ADR-23](archiv/adr/ADR-023-der-client-traegt-eine-einmalkennung-nicht-die-assertion.md) | Der Client trägt eine Einmalkennung, nicht die Assertion *(aufgegangen in ADR-21)* |
| [ADR-24](adr/ADR-024-eine-methode-haengt-von-einer-anderen-ab-indem.md) | Eine Methode hängt von einer anderen ab, indem sie deren Angabe verlangt |
| [ADR-25](adr/ADR-025-die-keycloak-konfiguration-steht-im-realm-nicht-in.md) | Die Keycloak-Konfiguration steht im Realm, nicht in der Container-Umgebung |
| [ADR-26](adr/ADR-026-api-vertrag-wird-generiert.md) | Der API-Vertrag wird generiert, nicht dreimal von Hand gepflegt |
| [ADR-27](adr/ADR-027-gemeinsame-typen-im-kernel-paket.md) | Gemeinsame Typen liegen im Paket `orchestrator.kernel` *(heute `orchestrator.domain`)* |
| [ADR-28](adr/ADR-028-demo-werte-abschaltbar.md) | Demo-Werte lassen sich abschalten |
| [ADR-29](adr/ADR-029-event-publication-registry-statt-eigener-outbox.md) | Die Event Publication Registry von Spring Modulith statt einer eigenen Outbox-Tabelle |
| [ADR-30](archiv/adr/ADR-030-eine-migration-je-modul.md) | Ein Flyway-Migrationsordner je Modul *(aufgegangen in ADR-16)* |
| [ADR-31](adr/ADR-031-freischaltcode-liegt-im-fremdsystem.md) | Der Freischaltcode liegt im Personenverzeichnis, `id_fsc` fragt es über einen Port |
| [ADR-32](adr/ADR-032-tool-sperre-und-reihenfolge-je-kanal.md) | Tool-Sperre und Reihenfolge je Kanaltyp |
| [ADR-33](adr/ADR-033-texte-als-vorlage-im-code.md) | Texte als deutsche Vorlage im Code, ausgeliefert als Referenz, formuliert per Prompt |
| [ADR-34](adr/ADR-034-personenverzeichnis-meldet-aenderungen.md) | Personenverzeichnis – Partnernummer, drei Rollen, Änderungen per Event ans Konto |
| [ADR-35](adr/ADR-035-betriebsanspruch-backend-kern-produktionsreif.md) | Betriebsanspruch – der Backend-Kern ist produktionsreif, Frontends und Umgebung folgen später |
| [ADR-36](adr/ADR-036-niveaus-und-ihre-nachweise.md) | Niveaus und ihre Nachweise – was nur behauptet ist, läuft nur im Demomodus |
| [ADR-37](adr/ADR-037-postfach-traegt-unidentifizierte-konten.md) | Bei einem nie identifizierten Konto genügt das Postfach auch für destruktive Aktionen |
| [ADR-38](adr/ADR-038-keycloak-liest-konten.md) | Keycloak liest die Konten, statt sie zu spiegeln |
| [ADR-39](adr/ADR-039-was-eine-kontoloeschung-ueberlebt.md) | Was eine Kontolöschung überlebt – das Änderungsprotokoll ohne Werte |

## Erkannte, bewusst zurückgestellte Verbesserungen

Diese bekannten Punkte sind bewusst **nicht** vollständig umgesetzt. Jeder davon verlangt eine
Entscheidung über Architektur oder Infrastruktur und lässt sich nicht mit einer Korrektur an einer
einzigen Stelle erledigen:

- **Skalierung von `orchestrator.dpop_proof_replay`** (siehe auch [09-dpop.md](09-dpop.md)
  Abschnitt 2): Der Schlüssel ist seit ADR-14 ein SHA-256-Hash fester Länge. Offen bleibt, die
  Tabelle nach Zeit zu partitionieren oder durch einen eigenen, dauerhaften Schlüssel-Wert-Speicher
  zu ersetzen. Das ist eine Entscheidung für die Produktivumgebung.
- **Lebenszyklus eines Kontos und Zusammenführen von Konten**: `Account` hat weder einen Status noch
  ein Feld `merged_into`. ADR-11 weist einen Konflikt um eine `person_id` bewusst ab, statt die Konten
  zusammenzuführen. Über die angestrebte Lebensdauer wird ein Zusammenführen aber zwangsläufig nötig,
  und ohne `merged_into` gibt es dann keinen Weg dorthin ohne Datenverlust.
- **Sehr viele Konten** (Größenordnung 10 Millionen, `account.claim` dann 20 bis 80 Millionen
  Zeilen). Die Demo erreicht das nie; für den Fall, dass das Modell so groß wird, gilt:
  - Die häufigen Abfragen lesen weiter gezielt einzelne Zeilen über schmale, indizierte Spalten
    (`account` über den Primärschlüssel, `account.anchor` über `(attribute_type, normalized_value)`,
    `orchestrator.device_account_link` über `binding_key_ref`), nie über Attribut-Wert-Paare.
  - Gesucht wird nur über normalisierte Werte (`normalizeAnchorValue`); `account.claim` braucht
    keinen Index für die Suche vom Wert zum Konto.
  - Bestehende Daten stellt man in wiederholbaren Portionen um, nicht in einer einzigen Transaktion.
  - Einen Abgleich aller Konten mit Keycloak gibt es nicht mehr: Keycloak liest ein Konto bei
    Bedarf einzeln nach, über den Primärschlüssel oder den E-Mail-Anker
    ([ADR-38](adr/ADR-038-keycloak-liest-konten.md)). Der frühere Voll-Abgleich, der alle Konto-IDs
    in den Speicher las (Issue `DPoP-demo-oa7d`), ist damit entfallen.

  Die Überlegungen stammen aus der archivierten Idee
  [claims-modell-und-vertrauensanker.md](archiv/claims-modell-und-vertrauensanker.md).

Alle drei verdienen eine eigene, sorgfältig geplante Überarbeitung, vor der der Entwurf entschieden wird.
