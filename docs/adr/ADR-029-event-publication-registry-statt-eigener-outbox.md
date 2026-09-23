# ADR-29: Spring Modulith' Event Publication Registry statt eigener Outbox-Tabelle

**Entscheidung.** Die Keycloak-Spiegelung läuft über `@ApplicationModuleListener`. Damit trägt
Spring Modulith die offenen Zustellungen in `orchestrator.event_publication` ein. Eine eigene
Outbox-Tabelle gibt es nicht.

## Das Problem

Die Spiegelung ist absichtlich best-effort: Ein fehlgeschlagener Keycloak-Aufruf darf eine bereits
committete Kontoänderung nicht nachträglich scheitern lassen. Der Fehler wurde geloggt und sonst
nichts. Als Wiederholung blieb nur „irgendwann ändert sich das Konto nochmal". Für ein Konto, das
sich nie wieder ändert, gab es also keine. Ergebnis: Das Konto existiert, der Keycloak-Nutzer fehlt,
eine Anmeldung ist unmöglich — und nirgends steht, dass das so ist.

## Erster Ansatz: eigene Tabelle

Zuerst hatte ich `orchestrator.kc_sync_outbox` gebaut: Eintrag in der Transaktion der
Kontoänderung, Schlüssel `accountId`, dazu ein Service für Erfolg/Fehler und ein geplanter Job für
die Wiederholung. Vier Klassen und eine Migration.

Das war überflüssig. Spring Modulith 2.1.1 liegt schon im Projekt und bringt genau dieses Verfahren
mit — die Event Publication Registry. Sie kann mehr als mein Nachbau:

| | eigene Tabelle | Registry |
|---|---|---|
| Eintrag vor dem Commit | ja | ja |
| Wiederholung im Betrieb | eigener `@Scheduled`-Job | `spring.modulith.events.staleness.*` |
| Wiederholung nach Neustart | nein | `republish-outstanding-events-on-restart` |
| Zustellversuche, Status, Zeitpunkt der letzten Wiederholung | nur Versuche | alle drei |
| Pflegeaufwand | unser | Framework |

## Was wir dafür aufgeben

Meine Tabelle war nach `accountId` verschlüsselt: Zehn Änderungen an einem Konto ergaben einen
offenen Eintrag. Die Registry führt einen Eintrag je Ereignis und Listener, also zehn. Das ist
vertretbar, weil die Spiegelung den aktuellen Kontostand überträgt und damit idempotent ist — zehn
Zustellungen führen zu zehn gleichen Upserts, nicht zu falschen Daten.

## Was dadurch anders wird

- Ein Fehler im Listener darf **nicht** mehr gefangen werden. Die Exception ist das Signal „nicht
  erledigt"; sie hält den Eintrag offen. Ein `catch`, das nur loggt, würde die Zustellung als
  erledigt markieren und den Fall endgültig verlieren.
- `@ApplicationModuleListener` ist zusätzlich `@Async`. Die Spiegelung läuft nicht mehr im
  Request-Thread.

## Kosten und Fallstricke

- Eine Abhängigkeit mehr (`spring-modulith-starter-jdbc`).
- `spring.modulith.events.jdbc.schema: orchestrator` ist zwingend. Ohne die Einstellung sucht die
  Registry die Tabelle im Standardschema, findet sie nicht und schreibt nichts — ohne Fehlermeldung.
  Genau das ist beim ersten Versuch passiert; `EventPublicationRegistryTest` prüft es deshalb.
- Die Tabelle legt Flyway an (`V5__event_publication.sql`), nicht Modulith selbst. Das Projekt legt
  jedes Schema per Migration an (ADR-16). Die Datei ist unverändert aus dem
  `spring-modulith-events-jdbc`-Jar übernommen und muss beim Anheben der Version verglichen werden.

## Eine Ausnahme

`KeycloakSessionLogoutListener` bleibt ein einfacher `@TransactionalEventListener` ohne Registry.
Eine nicht beendete Keycloak-Session läuft von selbst in wenigen Minuten ab. Ein fehlender
Keycloak-Nutzer dagegen bleibt. Nur der zweite Fall braucht eine Wiederholung.

Siehe [07-betrieb.md](../07-betrieb.md) Abschnitt 3a.
