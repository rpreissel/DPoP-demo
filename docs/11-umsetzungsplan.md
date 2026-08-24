# Umsetzungsplan

Der ursprüngliche Bauplan (Reihenfolge und Aufgaben, um den in [01](01-ueberblick.md)–[10](10-frontend.md)
beschriebenen Zielzustand zu erreichen) ist abgearbeitet — Status siehe
[README.md](README.md#umsetzungsstatus). Dieses Dokument hält nur noch die beiden bewussten
Scope-Entscheidungen fest, auf die der Code an mehreren Stellen verweist:

- **Keycloak-Anbindung explizit ausgeklammert**: die kc-Fassade (`/orchestrator/api/v1/kc/...`)
  und der echte `AuthContext`↔Keycloak-Tokenfluss sind nicht gebaut. `AuthContext` trägt die
  entsprechenden Felder (`keycloakSessionId`/`keycloakSubject`/`tokenHandle`) für die Form des
  Zielmodells, sie bleiben aber ungenutzt/`null` — der App-Kanal läuft ausschließlich über
  `bindingKeyRef` (DPoP).
- **`AuthPolicy.resolveAcr` (amr→acr-Abbildung) ist bewusst vorläufig**: Welche Kombination von
  Nachweisen welches Sicherheitsniveau ergibt, ist fachlich/regulatorisch offen und wird hier
  nicht endgültig festgeschrieben — die aktuelle Implementierung ist ein klar als solcher
  gekennzeichneter Platzhalter (`DefaultAuthPolicy`).

- **Flyway-Baseline statt Migration des Altcodes**: Der ursprüngliche Code stand auf einem klar
  älteren Stand (`Attempt`-Terminologie statt `ToolSession`/`ToolOutcome`, methodenspezifische
  URL-Pfade statt Tool-Namespace, kein `ToolDescriptor`/`AuthPolicy`, Klartext-TAN). Eine
  Migration Schritt für Schritt wäre aufwändiger und fehleranfälliger gewesen als ein sauberer
  Neubau — deshalb ersetzt `V1__schema.sql` die frühere `V1`–`V16`-Historie komplett, statt sie
  fortzuschreiben. Für eine Demo-App mit lokaler H2-Datei ohne produktive Daten war das
  vertretbar.
