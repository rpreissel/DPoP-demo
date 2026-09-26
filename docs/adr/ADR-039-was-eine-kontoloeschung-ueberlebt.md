# ADR-39: Was eine Kontolöschung überlebt – das Änderungsprotokoll ohne Werte

**Status**: entschieden und umgesetzt (2026-09-26). Review 2026-09, Fahrplan Phase F, Schritt 29.

**Entscheidung**: Jedes Konto hat ein append-only Änderungsprotokoll (`account.change_log`): dass und
wie etwas geschah, nie was. Feste Spalten halten, was jedes Ereignis hat: Konto-Id, Ereignis,
Verfahren bzw. Attributtyp, Niveau und Zeitpunkt. Was nur einzelne Ereignisse tragen, steht als JSON
in `details`, und jedes `details` nennt seinen eigenen `type` und seine `version`: Eine Zeile erklärt
sich damit auch Jahre später selbst, ohne den Code, der sie schrieb. Welche Schlüssel ein Ereignis
hat, legt allein `ChangeLog` fest, mit einer Funktion je Ereignis; eine neue Version gibt es, sobald
sich die Schlüssel eines Ereignisses ändern (`ChangeType.detailsVersion`). Keine Attributwerte,
keine Stammdaten. Das Protokoll überlebt die
Löschung des Kontos (kein Fremdschlüssel) und wird nach **10 Jahren ab der Löschung** abgeräumt
(`account.change-log.retention-years`, `ChangeLogRetention`).

Ereignisse: `IDENTIFIED`, `ATTRIBUTE_RETRACTED`, `METHOD_ADDED`, `METHOD_DEACTIVATED`,
`ACCOUNT_DELETED`, `ACCOUNT_ABSORBED`. Geschrieben in derselben Transaktion wie die Änderung selbst
(`ChangeLog`, `Propagation.MANDATORY`): Ein Ereignis gibt es genau dann, wenn es die Änderung gibt.

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
  war, steht jetzt im Änderungsprotokoll, der Rest ausführlicher im Journey-Log.
- **Das Journey-Log bleibt Fehlersuche**, kein Nachweis, und lebt 14 statt 30 Tage.
- **`account.identification` geht im Protokoll auf**: Es wurde nur geschrieben, nie gelesen.
  Das Ereignis `IDENTIFIED` trägt in `details` die Rolle (Identifizierung oder Zuordnung, ADR-18),
  die Referenz beim Anbieter (`provider`, `providerTxId`, `procedure`, `methodVersion`) und einen
  Hash des Gesehenen (`evidenceHash`). Damit lassen sich nachträglich alle Konten ermitteln, die ein
  bestimmtes Verfahren in einer bestimmten Version identifiziert hat, und ein einzelner Fall beim
  Anbieter nachprüfen. Übernimmt ein Konto ein vorläufiges (ADR-20), wandern dessen
  Identifizierungen mit, mit Herkunftsvermerk (`carriedFromAccountId`).
- **Eine Person ist über Name, Vorname und Geburtsdatum wiederzufinden**, auch nach der Löschung:
  Mehr kann jemand, dessen Konto übernommen und gelöscht wurde, oft nicht angeben. Jedes
  `IDENTIFIED` trägt dafür einen Suchschlüssel (`lookup_key`): einen HMAC über die drei **geprüften**
  Werte des Kontos, in der Schreibweise des Abgleichs (`passportForm`). Selbst angegebene Werte
  zählen nicht, sonst ließen sich Treffer unter fremdem Namen anlegen. Dazu kommt die `person_id`
  des Personenverzeichnisses, wo es eine gibt. Beides sind indizierte Spalten, keine Schlüssel in
  `details`: Wonach unter Millionen Zeilen gesucht wird, ist eine Spalte. Ein einfacher Hash taugt
  nicht, weil Name und Geburtsdatum sich mit Namenslisten durchprobieren ließen; das Geheimnis
  (`account.change-log.lookup-secret`) muss so lange bestehen wie das Protokoll. Jedes
  Identifizierungsverfahren muss alle drei Werte liefern – geprüft beim Start
  (`ToolHandlerRegistry`); `ident-fsc` meldet dafür jetzt auch das Geburtsdatum. Der Schlüssel ist
  ein pseudonymes Personendatum und stützt sich auf dieselbe Begründung wie das Protokoll.
- **Keine Dokument- oder Ausweisnummer**, weder im Protokoll noch angefordert: Sie darf nicht zum
  Verknüpfen verwendet werden (§ 20 PAuswG), und ein echter eID-Dienst gibt sie gar nicht heraus.
  Übernommen werden nur die genannten Referenzfelder, per Namen – `ChangeLog.identified` filtert den
  Bericht des Tools selbst, gleich was dieses liefert.
- **`auth_method.details` enthält nur noch, was das eigene Modul wieder liest** (Schlüssel-Referenz
  des Geräts, KOBIL-Gerätekennung). Wie ein Verfahren hinzukam – die Nachweise der Sitzung und der
  Kanal –, steht im Ereignis `METHOD_ADDED` (`amr`, `channel`). Der Thumbprint, die
  KOBIL-Nutzerkennung (liegt ohnehin im KOBIL-Modul) und die SMS-Anbieterangaben entfallen: Sie
  wurden nie gelesen.
- **Protokolle sind am Namen zu unterscheiden**: Der Name sagt den Zweck, das Suffix, ob es ein
  Nachweis ist. `account.change_log` (Kontoänderungen, 10 Jahre nach der Löschung),
  `account.sign_in_log` (Anmeldungen, 6 Monate, geht mit dem Konto),
  `orchestrator.journey_trace` (Fehlersuche, 14 Tage) – Nachweise enden auf `_log`, die Spur zur
  Fehlersuche auf `_trace`.
- **Anmeldungen gehören nicht hierher** (siehe Offen): Sie sind um Größenordnungen häufiger, und
  eine Anmeldehistorie über Jahre nach der Löschung ließe sich mit der Speicherbegrenzung nicht
  begründen.

**Erwogene Alternativen**:

- **Kaskaden entfernen**: verworfen, siehe oben.
- **Eine Spalte je Feld** bzw. zusammengesetzte Texte (`ANKER:grund`, `provider=…;tx=…`):
  verworfen – die Spalten wären je Ereignis anders zu lesen, die Texte müsste jeder Leser selbst
  zerlegen. Die Suche „alle Konten eines Verfahrens in Version X“ ist eine seltene Betreiberabfrage;
  auf PostgreSQL lässt sie sich bei Bedarf mit einem Ausdrucksindex auf `details` beschleunigen.
- **Nichts überlebt**: verworfen – bei einer strittigen Übernahme gäbe es keinen Nachweis, wer wann
  womit identifiziert war.
- **Das Journey-Log als Nachweis verlängern**: verworfen – zu detailreich für Jahre, und nicht jede
  Kontoänderung läuft über eine Journey (Personenverzeichnis, Passwortwechsel über Keycloak, Löschen
  durch den Betreiber).

**Offen**: Anmeldungen selbst (wer sich wann mit welchem Niveau angemeldet hat) stehen nur 14 Tage im
Journey-Log. Entschieden (2026-09-26), noch nicht gebaut: ein eigenes Anmeldeprotokoll
(`account.sign_in_log`) in derselben
Form (feste Spalten, `details` mit `type` und `version`) für erfolgreiche und fehlgeschlagene
Anmeldungen und Step-ups sowie Sperren durch das Versuchslimit, nur kontobezogen. Frist 6 Monate,
einstellbar; es wird mit dem Konto gelöscht – den Nachweis über die Löschung hinaus leistet dieses
Protokoll hier.
