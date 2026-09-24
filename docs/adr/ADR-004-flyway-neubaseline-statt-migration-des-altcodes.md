# ADR-4: Neue Flyway-Ausgangsbasis statt Migration des Altcodes

**Status:** Abgelöst durch [ADR-14](ADR-014-schema-zusammengefuehrt-das-konto-als-sperrpunkt-eine-wahrheit.md)
und [ADR-16](ADR-016-ein-datenbankschema-je-modul-statt-namenspraefix.md). Die Datei `V1__schema.sql`,
um die es hier ging, gibt es nicht mehr.

**Was damals entschieden wurde:** Ein sauberer Neubau des Schemas ersetzte die Migrationen des
ursprünglichen Codes, statt sie Schritt für Schritt weiterzuführen. Der alte Stand war vom Ziel zu
weit entfernt (andere Begriffe, kein `ToolDescriptor`, keine `AuthPolicy`, TANs im Klartext). Eine
schrittweise Umstellung hätte mehr Zwischenzustände und damit mehr Fehlerquellen erzeugt als ein
Neubau, und bei einer Demo mit lokaler H2-Datei gingen dabei keine Daten verloren.

Seitdem gab es zwei weitere neue Ausgangsbasen: das zusammengeführte Kontomodell (ADR-14) und die
Aufteilung in einen Migrationsordner je Modul (ADR-16).

**Was davon bleibt:** eine Regel, die jetzt in
[`db/migration/KONVENTIONEN.md`](../../src/main/resources/db/migration/KONVENTIONEN.md) steht: Eine neue
Ausgangsbasis ist nur ohne Produktivdaten vertretbar. Sobald es solche Daten gibt, werden Migrationen
nur noch ergänzt.
