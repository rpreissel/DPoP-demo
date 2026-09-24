# Architekturentscheidungen

Die großen Entscheidungen dieses Projekts, jeweils mit der ernsthaft erwogenen Alternative und
mit dem, was die gewählte Lösung kostet. Was die Lösung *ist*, steht in den verlinkten Kapiteln — hier steht nur das
*Warum*.

---

## Die Entscheidungen

Eine Datei je Entscheidung, unter [`adr/`](adr/). Vorher standen alle in dieser einen Datei
(66 KB): Wer eine Entscheidung nachlesen wollte, musste alle laden, und jede Änderung landete als
Diff mitten im Fließtext.

| ADR | Entscheidung |
|---|---|
| [ADR-1](adr/ADR-001-ein-controller-je-tool-kein-generischer-dispatcher.md) | Ein Controller je Tool, kein generischer Dispatcher |
| [ADR-2](adr/ADR-002-zustand-statt-vererbung-bei-authjourney.md) | Zustand statt Vererbung bei `AuthJourney` |
| [ADR-3](adr/ADR-003-channelsession-bewusst-kurzlebig-geraete-identitaet-in-deviceaccountlink.md) | `ChannelSession` bewusst kurzlebig, Geräte-Identität in `DeviceAccountLink` |
| [ADR-4](adr/ADR-004-flyway-neubaseline-statt-migration-des-altcodes.md) | Flyway-Neubaseline statt Migration des Altcodes |
| [ADR-5](adr/ADR-005-drei-obergrenzen-fuer-das-sicherheitsniveau.md) | Drei Obergrenzen für das Sicherheitsniveau |
| [ADR-6](adr/ADR-006-next-als-reine-adresse-feste-routing-tabelle-statt.md) | `next` als reine Adresse, feste Routing-Tabelle statt HATEOAS |
| [ADR-7](adr/ADR-007-web-kanal-ohne-mtls-signierte-request-assertion-statt.md) | Web-Kanal ohne mTLS, signierte Request-Assertion statt Client-Zertifikat |
| [ADR-8](adr/ADR-008-keycloak-fuehrt-seine-eigenen-nativen-schritte-selbst-statt.md) | Keycloak führt seine eigenen nativen Schritte selbst, statt alles zu delegieren oder über Identity-Brokering zu gehen |
| [ADR-9](adr/ADR-009-profilabhaengiges-token-retrieval-account-keypair-custom-oauth2-grant.md) | Profilabhängiges Token-Retrieval — Account-Keypair + custom OAuth2-Grant statt geteiltem Admin-Secret |
| [ADR-10](adr/ADR-010-interessent-ist-konto-zustand-kein-eigener-authintent.md) | Interessent ist Konto-Zustand, kein eigener AuthIntent |
| [ADR-11](adr/ADR-011-kontouebergreifender-person-id-konflikt-ist-abweisung-merge-nie.md) | Kontoübergreifender person_id-Konflikt ist Abweisung, Merge nie automatisiert |
| [ADR-12](adr/ADR-012-ein-widerruf-ist-eine-eigene-zeile-mit-eigenem.md) | Ein Widerruf ist eine eigene Zeile mit eigenem Vertrauensanker |
| [ADR-13](adr/ADR-013-account-domaenentypen-ueber-eigene-attributeconverter-nicht-enumerated.md) | Account-Domänentypen über eigene `AttributeConverter`, nicht `@Enumerated` |
| [ADR-14](adr/ADR-014-schema-zusammengefuehrt-das-konto-als-sperrpunkt-eine-wahrheit.md) | Schema zusammengeführt — das Konto als Sperrpunkt, eine Wahrheit je Fakt |
| [ADR-15](adr/ADR-015-nachweise-und-ausgestellte-tokens-in-getrennten-tabellen.md) | Nachweise und ausgestellte Tokens in getrennten Tabellen |
| [ADR-16](adr/ADR-016-ein-datenbankschema-je-modul-statt-namenspraefix.md) | Ein Datenbankschema je Modul statt Namenspräfix |
| [ADR-17](adr/ADR-017-adresse-bestaetigen-und-e-mail-login-einrichten-sind.md) | Adresse bestätigen und E-Mail-Login einrichten sind zwei Akte |
| [ADR-18](adr/ADR-018-bestaetigen-und-zuordnen-sind-zwei-akte.md) | Bestätigen und Zuordnen sind zwei Akte |
| [ADR-19](adr/ADR-019-aufloesung-nur-ueber-anker-die-eid-restricted-id.md) | Auflösung nur über Anker — die eID-`restricted_id` wird einer |
| [ADR-20](adr/ADR-020-ein-vorlaeufiges-konto-geht-im-gefundenen-auf-statt.md) | Ein vorläufiges Konto geht im gefundenen auf, statt den Lauf abzuweisen |
| [ADR-21](adr/ADR-021-der-kobil-pin-liegt-im-backend-und-das.md) | Der KOBIL-PIN liegt im Backend — und das Zugangsmittel zählt trotzdem |
| [ADR-22](adr/ADR-022-der-verwahrte-pin-liegt-im-klartext-demo-rahmen.md) | Der verwahrte PIN liegt im Klartext — Demo-Rahmen, benannt statt verschwiegen |
| [ADR-23](adr/ADR-023-der-client-traegt-eine-einmalkennung-nicht-die-assertion.md) | Der Client trägt eine Einmalkennung, nicht die Assertion |
| [ADR-24](adr/ADR-024-eine-methode-haengt-von-einer-anderen-ab-indem.md) | Eine Methode hängt von einer anderen ab, indem sie deren Angabe verlangt |
| [ADR-25](adr/ADR-025-die-keycloak-konfiguration-steht-im-realm-nicht-in.md) | Die Keycloak-Konfiguration steht im Realm, nicht in der Container-Umgebung |
| [ADR-26](adr/ADR-026-api-vertrag-wird-generiert.md) | Der API-Vertrag wird generiert, nicht dreimal von Hand gepflegt |
| [ADR-27](adr/ADR-027-gemeinsame-typen-im-kernel-paket.md) | Gemeinsame Typen liegen im Paket `orchestrator.kernel` |
| [ADR-28](adr/ADR-028-demo-werte-abschaltbar.md) | Demo-Werte lassen sich abschalten |
| [ADR-29](adr/ADR-029-event-publication-registry-statt-eigener-outbox.md) | Spring Modulith' Event Publication Registry statt eigener Outbox-Tabelle |
| [ADR-30](adr/ADR-030-eine-migration-je-modul.md) | Ein Flyway-Migrationsordner je Modul |
| [ADR-31](adr/ADR-031-freischaltcode-liegt-im-fremdsystem.md) | Der Freischaltcode liegt im Personenregister, `id_fsc` fragt es direkt |
| [ADR-32](adr/ADR-032-tool-sperre-und-reihenfolge-je-kanal.md) | Tool-Sperre und Reihenfolge je Kanaltyp |
| [ADR-33](adr/ADR-033-texte-als-vorlage-im-code.md) | Texte als deutsche Vorlage im Code, ausgeliefert als Referenz, formuliert per Prompt |

## Erkannte, bewusst zurückgestellte Verbesserungen

Bekannte Befunde, die bewusst **nicht** vollständig umgesetzt sind — jeder davon ist eine
Architektur- oder Infrastrukturentscheidung, kein Fix, der an einer Stelle abzuschließen wäre:

- **`orchestrator.dpop_proof_replay`-Skalierung** (siehe auch [09-dpop.md](09-dpop.md) Abschnitt 2): Der
  Schlüssel ist seit ADR-14 ein fester SHA-256-Hash. Offen bleibt die Zeitpartitionierung bzw. ein
  separater persistenter KV-Store — eine Entscheidung für den Produktivstack.
- **Konto-Lebenszyklus und Merge-Pfad**: `Account` kennt keinen Status und kein
  `merged_into`. ADR-11 weist einen `person_id`-Konflikt
  bewusst ab, statt zu mergen — über die angestrebte Lebensdauer wird ein Merge aber
  zwangsläufig nötig, und ohne `merged_into` gibt es dann keinen verlustfreien Weg dorthin.

Beide verdienen einen eigenen, sorgfältig geplanten Durchgang mit Entwurfsentscheidung vorab.
