# ADR-29: Die Event Publication Registry von Spring Modulith statt einer eigenen Outbox-Tabelle

> **Nachtrag 2026-09-26 (zweite Bewertung, A-1):** Die Keycloak-Spiegelung, für die dieser ADR
> entstand, gibt es seit [ADR-38](ADR-038-keycloak-liest-konten.md) nicht mehr: Keycloak liest die
> Konten selbst beim Orchestrator nach. Die Registry trägt heute zwei Listener:
> `KeycloakAccountRemovalListener` räumt nach `AccountDeleted` die Daten ab, die Keycloak selbst zu
> dem Konto hält (Sitzungen, Fehlversuche, Zustimmungen), und `PersonChangeListener` übernimmt eine
> Änderung aus dem Personenverzeichnis ins Konto ([ADR-34](ADR-034-personenverzeichnis-meldet-aenderungen.md)).
> Die Abschnitte „Das Problem“, „Erster Ansatz“ und „Was wir dafür aufgeben“ beschreiben den Stand
> der Spiegelung. Die Regeln unter „Was dadurch anders wird“ und „Kosten und Stolpersteine“ gelten
> weiter.

**Entscheidung.** Ereignisse, deren Zustellung nicht verloren gehen darf, laufen über
`@ApplicationModuleListener` (anfangs die Keycloak-Spiegelung, heute das Abräumen eines gelöschten
Kontos in Keycloak und die Übernahme von Änderungen aus dem Personenverzeichnis). Damit trägt Spring
Modulith die offenen Zustellungen in `orchestrator.event_publication` ein. Eine eigene Outbox-Tabelle
gibt es nicht.

## Das Problem

Die Spiegelung lief absichtlich ohne Garantie: Ein fehlgeschlagener Aufruf bei Keycloak durfte eine
bereits festgeschriebene Kontoänderung nicht nachträglich scheitern lassen. Der Fehler wurde ins Log
geschrieben, sonst geschah nichts. Wiederholt wurde nur, wenn sich das Konto irgendwann noch einmal
änderte; bei einem Konto, das sich nie wieder ändert, also nie. Das Ergebnis: Das Konto existiert,
der Nutzer in Keycloak fehlt, eine Anmeldung ist unmöglich, und nirgends steht, dass es so ist.

## Erster Ansatz: eigene Tabelle

Zuerst hatte ich `orchestrator.kc_sync_outbox` gebaut: Eintrag in der Transaktion der
Kontoänderung, Schlüssel `accountId`, dazu ein Service für Erfolg/Fehler und ein geplanter Job für
die Wiederholung. Vier Klassen und eine Migration.

Das war überflüssig. Spring Modulith 2.1.1 ist schon im Projekt und bringt genau dieses Verfahren
mit, die Event Publication Registry. Sie kann mehr als mein Nachbau:

| | eigene Tabelle | Registry |
|---|---|---|
| Eintrag vor dem Festschreiben | ja | ja |
| Wiederholung im Betrieb | eigener `@Scheduled`-Job | `spring.modulith.events.staleness.*` |
| Wiederholung nach Neustart | nein | `republish-outstanding-events-on-restart` |
| Zustellversuche, Status, Zeitpunkt der letzten Wiederholung | nur Versuche | alle drei |
| Pflegeaufwand | unser | Framework |

## Was wir dafür aufgeben

Meine Tabelle hatte `accountId` als Schlüssel: Zehn Änderungen an einem Konto ergaben einen offenen
Eintrag. Die Registry führt einen Eintrag je Ereignis und Listener, also zehn. Das ist vertretbar,
weil die Spiegelung immer den aktuellen Stand des Kontos übertrug und sich deshalb beliebig oft
wiederholen ließ: Zehn Zustellungen schreiben zehnmal dasselbe und erzeugen keine falschen Daten.

## Was dadurch anders wird

- Ein Fehler im Listener darf **nicht** mehr gefangen werden. Die Exception ist das Signal „nicht
  erledigt"; sie hält den Eintrag offen. Ein `catch`, das den Fehler nur ins Log schreibt,
  würde die Zustellung als erledigt markieren und den Fall endgültig verlieren.
- `@ApplicationModuleListener` ist zusätzlich `@Async`. Der Listener läuft nicht im Thread der
  Anfrage.

## Kosten und Stolpersteine

- Eine Abhängigkeit mehr (`spring-modulith-starter-jdbc`).
- `spring.modulith.events.jdbc.schema: orchestrator` ist zwingend. Ohne die Einstellung sucht die
  Registry die Tabelle im Standardschema, findet sie nicht und schreibt nichts — ohne Fehlermeldung.
  Genau das ist beim ersten Versuch passiert; `EventPublicationRegistryTest` prüft es deshalb.
- Die Tabelle legt Flyway an (`orchestrator/V15__event_publication.sql`), nicht Modulith selbst. Das Projekt legt
  jedes Schema per Migration an (ADR-16). Die Datei ist unverändert aus dem
  `spring-modulith-events-jdbc`-Jar übernommen und muss beim Wechsel auf eine neue Version damit verglichen
  werden.

## Eine Ausnahme

`KeycloakSessionLogoutListener` bleibt ein einfacher `@TransactionalEventListener` ohne Registry.
Eine nicht beendete Sitzung in Keycloak läuft in wenigen Minuten von selbst ab. Die Daten, die
Keycloak zu einem gelöschten Konto hält, blieben dagegen ohne Abräumen liegen. Nur der zweite Fall
braucht eine Wiederholung.

Siehe [07-betrieb.md](../07-betrieb.md) Abschnitt 3a.
