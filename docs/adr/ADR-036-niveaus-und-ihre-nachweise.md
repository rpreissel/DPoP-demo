# ADR-36: Niveaus und ihre Nachweise – was nur behauptet ist, läuft nur im Demomodus

**Status**: entschieden (2026-09-25). Review 2026-09, M-1 und P-4; Fahrplan Phase E, Schritt 22.

**Entscheidung**: Ein Verfahren vergibt nur das Niveau, das der Server selbst nachprüfen kann oder
das ein Fremdsystem über seinen Port zusagt ([ADR-35](ADR-035-betriebsanspruch-backend-kern-produktionsreif.md)).
Ein Verfahren, das mehr vergibt, als es beweisen kann, erklärt das selbst: `ToolDescriptor.demoOnly`
mit Begründung. Solche Verfahren sind nur im **Demomodus** (`demo.mode=true`) verfügbar. Mit
`demo.mode=false` bietet der Kern sie nicht an, lässt sie nicht aktivieren, und keine
Betreiber-Einstellung schaltet sie wieder ein (`ToolAvailabilityService`).

**Anlass**: `auth-device`/`enroll-device` vergeben loa2, weil der Gerätebeweis neben dem Besitz des
Schlüssels eine Nutzerverifikation (PIN, Biometrie) meldet. Diese Meldung ist ein Feld in einem JWT,
das die App selbst signiert. Eine manipulierte App meldet, was sie will. Der zweite Faktor ist damit
behauptet, nicht bewiesen.

Aus demselben Grund sind die Verfahren `demoOnly`, deren Niveau auf einer **simulierten Gegenstelle**
beruht: `enroll-kobil`/`auth-kobil` (`kobil_mock`), `ident-eid` (simulierte Kartenlesung) und
`ident-nect` (`nect_mock`). Der Port-Vertrag beschreibt, was ein echtes System zusagen müsste – aber
in dieser Instanz sagt es niemand zu. Ein Anschluss an das echte System macht das Verfahren wieder
allgemein verfügbar; dann entfällt die Erklärung.

**Warum eine Erklärung am Verfahren und kein Modus in seinen Werten**: Erwogen war, den Gerätefaktor
außerhalb des Demomodus auf `{possession}`/loa1 herabzustufen. Dann hingen `maxAcr` und
`factorTypes` vom Modus ab. Die Policy wählt aber nach diesen Werten aus, welche Verfahren sie
anbietet; Werte, die je nach Modus anders lauten, müssten überall mitgedacht werden. Die Erklärung am
Verfahren hält die Werte fest und macht die Ausnahme zu einer Zeile, die sich prüfen lässt. Ein
Gerätefaktor mit echter Plattform-Attestation (etwa WebAuthn mit geprüfter Attestierung) wäre ein
eigenes Verfahren ohne `demoOnly`, kein Modus dieses einen.

**Verhältnis zu ADR-35** („kein demo-only im Kern“): Die Sicherheitsaussagen des Kerns gelten für
`demo.mode=false`. Dort ist jedes `demoOnly`-Verfahren nachweislich aus
(`DemoOnlyToolAvailabilityTest`). Im Demomodus läuft der Kern mit benannten, einzeln begründeten
Ausnahmen – nicht mit unerklärten.

**Der Demomodus**: `demo.mode` (Umgebung `DEMO_MODE`, Voreinstellung `true` für diese Demo-Instanz).
Er schaltet zwei Dinge: die `demoOnly`-Verfahren und, solange nicht eigens gesetzt, die Demo-Werte in
Antworten (`demo.disclosure`, [ADR-28](ADR-028-demo-werte-abschaltbar.md)). Jede Instanz mit echten
Personen läuft mit `demo.mode=false`.

**Niveaus und was sie tragen** (Stand dieser Entscheidung):

- **loa1:** ein Faktor, den der Server selbst prüft – eine SMS-TAN (Besitz der Nummer), ein Passwort
  oder ein Code an die E-Mail-Adresse (Wissen). Die Policy hebt zwei verschiedene Faktorarten
  zusammen auf loa2 (docs/04-orchestrierung.md #8).
- **loa2 aus einem Verfahren:**
  - `ident-fsc`: Besitz eines per Post an die Person gesandten Codes; Einmaligkeit und Ablauf sagt
    das Personenverzeichnis über seinen Port zu ([ADR-31](ADR-031-freischaltcode-liegt-im-fremdsystem.md)).
  - `auth-kobil`/`enroll-kobil`: **nur Demomodus** – das Ergebnis käme vom KOBIL-Server über den
    Port; die Gegenstelle ist simuliert. Was ein echtes KOBIL zusagen muss, legt Fahrplan-Schritt 27 fest.
  - `auth-qr`/`auth-qr-lookup`: die Bestätigung aus einer App-Sitzung, die selbst frisch loa2
    nachgewiesen hat (`CONFIRM_PEER_LOGIN`).
  - `ident-kvnr`: kein eigener Nachweis; es trägt nur zusammen mit der schon bezeugten Identität
    (`requires`, ADR-18).
  - `auth-device`/`enroll-device`: **nur Demomodus** – siehe oben.
- **loa3:** `ident-eid` und `ident-nect` – **nur Demomodus**. Das Ergebnis muss serverseitig vom
  eID-Server bzw. von Nect kommen, nie aus Client-Angaben (Port-Vertrag, ADR-35); in dieser Instanz
  sind beide simuliert. Mit `demo.mode=false` erreicht damit derzeit kein Verfahren loa3.

**Zusätzlich** (unabhängig vom Modus): Der Geräteschlüssel muss ein anderer sein als der
DPoP-Schlüssel des Kanals, sonst lehnt `enroll-device` ab (`EnrollDeviceFlow`). Ein zweiter Faktor,
der derselbe Schlüssel wie die Kanalbindung ist, fügt nichts hinzu.
