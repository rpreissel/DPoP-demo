# ADR-39: Was eine Kontolöschung überlebt – das Audit-Protokoll ohne Werte

**Status**: entschieden und umgesetzt (2026-09-26). Review 2026-09, Fahrplan Phase F, Schritt 29.

**Entscheidung**: Jedes Konto hat ein append-only Audit-Protokoll (`account.audit_event`): dass und
wie etwas geschah, nie was. Es hält Konto-Id, Ereignis, Verfahren bzw. Attributtyp, Niveau, Quelle
und Zeitpunkt – keine Attributwerte, keine Stammdaten, keine Details. Das Protokoll überlebt die
Löschung des Kontos (kein Fremdschlüssel) und wird nach **10 Jahren ab der Löschung** abgeräumt
(`account.audit.retention-years`, `AuditRetention`).

Ereignisse: `IDENTIFIED`, `ATTRIBUTE_RETRACTED`, `METHOD_ADDED`, `METHOD_DEACTIVATED`,
`ACCOUNT_DELETED`, `ACCOUNT_ABSORBED`. Geschrieben in derselben Transaktion wie die Änderung selbst
(`AuditLog`, `Propagation.MANDATORY`): Ein Ereignis gibt es genau dann, wenn es die Änderung gibt.

**Warum nicht einfach die Kaskaden entfernen** (so der Vorschlag im Review): Die bisherigen
Tabellen tragen Werte – `claim` die Attributwerte, `identification.details` etwa Dokumentnummer und
Ort, `auth_method.details` Schlüssel-Thumbprints. Sie über die Löschung hinaus zu behalten, hieße
genau das aufzubewahren, was die Datenminimierung (Art. 5 DSGVO) verbietet. Sie verschwinden deshalb
weiter mit dem Konto; überlebt nur, was als Nachweis nötig ist.

**Datenschutz**: Grundsätzlich wird bei einer Kontolöschung gelöscht (Art. 17 DSGVO). Das Protokoll
stützt sich auf Art. 17 Abs. 3 lit. e (Verteidigung von Rechtsansprüchen, etwa bei einer strittigen
Kontoübernahme) bzw. lit. b, wo eine gesetzliche Aufbewahrungspflicht besteht. Die 10 Jahre
orientieren sich an der längsten Verjährung (§ 199 BGB); ohne benannte gesetzliche Pflicht wären
3 Jahre (§ 195 BGB) der vorsichtigere Wert. **Die Frist ist von der Datenschutzbeauftragten zu
bestätigen** und deshalb einstellbar.

**Zugleich entfallen und festgelegt**:

- **`orchestrator.session_event` entfällt.** Es wurde nur geschrieben, nie gelesen; was darin Nachweis
  war, steht jetzt im Audit-Protokoll, der Rest ausführlicher im Journey-Log.
- **Das Journey-Log bleibt Fehlersuche**, kein Nachweis, und lebt 14 statt 30 Tage.

**Erwogene Alternativen**:

- **Kaskaden entfernen**: verworfen, siehe oben.
- **Nichts überlebt**: verworfen – bei einer strittigen Übernahme gäbe es keinen Nachweis, wer wann
  womit identifiziert war.
- **Das Journey-Log als Nachweis verlängern**: verworfen – zu detailreich für Jahre, und nicht jede
  Kontoänderung läuft über eine Journey (Personenverzeichnis, Passwortwechsel über Keycloak, Löschen
  durch den Betreiber).

**Offen**: Anmeldungen selbst (wer sich wann mit welchem Niveau angemeldet hat) stehen nur 14 Tage im
Journey-Log. Bei 10 Millionen Nutzern ist das zu viel Volumen für das Audit-Protokoll; ein eigenes
Sicherheitsprotokoll mit kürzerer Frist (etwa 6–12 Monate) wäre der Ort dafür.
