# Idee: Identifikation über Nect (`ident-nect`)

Status: **Als Demo mit simuliertem Dienst umgesetzt** (2026-09-23), nur im App-Kanal. Offen sind die
echte Anbindung (Abschnitt 9) und der Web-Kanal (Abschnitt 8). Abweichungen vom Konzept stehen in
Abschnitt 12. Das Dokument fasst die beiden früheren Papiere „ext-ident als Tool anbinden“ und
„ext-ident in den Modulith integrieren“ (2026-08-31) zusammen und gleicht sie mit dem heutigen Code
ab. Neu ist ein **simuliertes Fremdsystem** mit Sprungseite und Formularen für eID, ePass und
EUDI-Wallet, damit sich das Verfahren in der Demo ohne Zugang zu Nect durchspielen lässt.

---

## 1) Ausgangslage

Der Microservice `ext-ident` identifiziert Personen über den Dienstleister **Nect**:

1. Ein aufrufendes Backend erzeugt ein signiertes **Order-JWT** (`sub`, `challenge`,
   `data.callback_uri`, `data.process`, `data.loa`) und reicht es mit einer PKCE-Challenge ein
   (`POST /public/v1/tkapp`). ext-ident legt einen Vorgang (`IdentCase`) an und gibt eine
   `redirect_uri` zurück.
2. Der Nutzer durchläuft auf der **Sprungseite von Nect** das Verfahren (eID, ePass oder Video-Ident).
3. Nect meldet das Ergebnis asynchron an ext-ident (`PUT /protected/cases/{caseId}`) und leitet
   den Browser zur `callback_uri` zurück.
4. Das Backend löst den Vorgang ein (`POST /public/v1/tkapp/{caseId}` mit `code_verifier`). ext-ident
   holt die Ausweisdaten, gleicht sie mit TKeasy ab und stellt ein signiertes **Confirmation-JWT** aus.

`ext-ident` besteht aus `ext-ident-server` (etwa 45 Klassen: Prüfung der Order, Lebenszyklus der
Vorgänge, Anbindung an Nect, Abgleich mit TKeasy, Confirmation, Beenden der Vorgänge),
`nect-rest-client` (etwa 15 Klassen) und `ext-ident-devtools` (ein simuliertes Nect mit
Thymeleaf-Oberfläche für Testumgebungen).

In dieser Demo gab es damals zwei Tools zur Identifizierung (`ident-fsc`, `ident-eid`) und ein Tool
zur Zuordnung (`ident-kvnr`, `CORRELATION`). `ident-nect` wäre das erste Tool, bei dem der Nutzer
die Anwendung **per Weiterleitung** verlässt.

---

## 2) Was sich seit den ersten Papieren geändert hat

| Damals | Heute | Folge für Nect |
|---|---|---|
| „Tools arbeiten rein intern; eine direkte Abhängigkeit zwischen Backends bricht das Muster“ | Fremdsysteme sind eigene Module mit einer direkten, deklarierten Abhängigkeit: `auth_kobil → kobil_mock` (`KobilSsms`), `id_fsc → ext_personenverzeichnis` (`Freischaltcodes`, ADR-31) | Der Einwand aus Sicht der Architektur entfällt. Nect wird ein weiteres Fremdsystem: in der Demo `nect_mock`, dahinter austauschbar gegen die echte Anbindung |
| Tool liefert `Completed.Identified(personId, …)` | ADR-18: Wer nur Ausweisdaten bestätigt, löst keine Person auf (`ident-eid`); die Zuordnung übernimmt `ident-kvnr` | Abschnitt 4 entscheidet die Rolle neu |
| Nur das Frontend der App betrachtet | Die Tools im Web-Kanal zeigt die Keycloak-Erweiterung an (`WebToolRenderer`); Sperre je Kanal (ADR-32) | Start nur im App-Kanal, Web später (Abschnitt 8) |
| „Das Sitzungs-Cookie übersteht die Weiterleitung“ | Der App-Kanal hat keine Cookies: `channelSessionId` in localStorage, DPoP-Schlüssel in IndexedDB, Fortsetzen per `GET /channels/{id}`; Muster für Deep-Links `/app/?intent=confirm_peer_login&pairingCode=…` | Rücksprung als Deep-Link `/app/?tool=ident-nect&caseId=…` (Abschnitt 6) |
| Tabellen ohne Schema (`nect_ident_case`) | Ein Datenbankschema je Modul (ADR-16), Arbeitsdaten als `<modul>.<rolle>_tool_session` | `id_nect.ident_tool_session`, `nect_mock.ident_case` |
| Beenden der Vorgänge per `@Scheduled` und ShedLock | Eine einzige Instanz (`DeploymentTopologyCheck`); die Arbeitsdaten der Tools räumt je Modul ein `ToolSessionSweeper` auf | Kein ShedLock; Aufräumen wie bei den übrigen Tools |
| Descriptor mit `toolId`/`method`/`role`/`factorTypes`/`maxAcr` | Dazu `claims` (`ClaimDeclaration`), `startStep`; `Completed.Identified` trägt Claims und `auditDetails` | Descriptor in Abschnitt 5 entsprechend |

---

## 3) Zielbild

```
App-Kanal (Browser-Tab "Smartphone")          simuliertes Nect (Browser-Tab bzw. Weiterleitung)
        │                                                │
        │ 1. ident-nect aktivieren                      │
        ▼                                                │
  id_nect ── NectService (Abhängigkeit) ──► nect_mock  │
        │   createCase(callback, loa)   Vorgang anlegen  │
        │◄──────────── jumpUrl ─────────────┘            │
        │                                                │
        │ 2. Weiterleitung zur Sprungseite ────────────────►  /nect/?case=…
        │                                                │   Verfahren wählen:
        │                                                │   eID · ePass · EUDI-Wallet
        │                                                │   Formular ausfüllen, abschließen
        │ 3. Rücksprung  ◄───────────────────────────────┘   /app/?tool=ident-nect&caseId=…
        │
        │ 4. PATCH {caseId}  →  id_nect holt Ergebnis beim Fremdsystem
        ▼
  ToolOutcome.Completed.Identified (Kartendaten als Claims)  →  weiter wie bei ident-eid
```

- **`id_nect`** ist das Tool-Modul (Descriptor, Controller, Handler) nach dem Muster von `id_eid`. Es
  kennt Nect nur über eine schmale Schnittstelle `NectService`.
- **`nect_mock`** ist das simulierte Fremdsystem nach dem Muster von `kobil_mock`:
  `allowedDependencies = []`, eigenes Schema und zwei Schnittstellen, `NectService` für unser Backend
  und die HTTP-Schnittstelle `/mock-nect/*` für die Sprungseite.
- Die **echte Anbindung** (Abschnitt 9) ersetzt später nur die Umsetzung von `NectService`. Tool,
  Rücksprung im Frontend und Ergebnis bleiben gleich.

---

## 4) Rolle: Kartendaten bestätigen, keine Person finden

Nect bestätigt, was auf dem Dokument steht. Eine KVNR oder Partnernummer trägt kein Ausweis,
kein Pass und keine Wallet-PID. ext-ident gleicht zwar selbst mit TKeasy ab, weil ihm die Order
den `sub` schon nennt. In unserem Ablauf ist beim Identifizieren aber noch keine Person bekannt.

**Empfehlung: angleichen an `ident-eid`** (ADR-18/19): `ident-nect` meldet die bestätigten
Attribute als Claims mit `ClaimSource.of("ident-nect")`, keine `PERSON_ID`. Die Zuordnung zur
Person im Personenverzeichnis läuft danach wie heute über `ident-kvnr`. So gibt es keinen zweiten Weg
für dieselbe Frage.

Die Alternative wäre, dass `ident-nect` selbst `PersonDirectory.matchesStammdaten` aufruft und wie
`ident-fsc` eine `PERSON_ID` meldet. Das wäre eine zweite Logik zur Zuordnung neben `ident-kvnr` und
lohnt sich nur, wenn Nect künftig mit bekannter KVNR gestartet wird (erneute Identifizierung eines
bekannten Kontos).

**Wiedererkennung:** `ident-eid` nutzt die `restrictedId` als lokalen Anker. Die Entsprechung je
Verfahren:

| Verfahren | Anker-Kandidat | Bemerkung |
|---|---|---|
| eID | Restricted Identifier (Pseudonym je Diensteanbieter) | wie `ident-eid` |
| ePass | Dokumentnummer + Ausstellerstaat | wechselt mit jedem neuen Pass – wie eine neue Karte (ADR-19) |
| EUDI-Wallet | – | die PID enthält kein Pseudonym gegenüber der vertrauenden Stelle (Abschnitt 7); Pseudonyme laufen im ARF getrennt über Passkeys |

---

## 5) Tool `ident-nect`

```kotlin
object IdentNectDescriptor : ToolDescriptor {
    override val toolId = ToolId("ident-nect")
    override val method = "nect"
    override val role = MethodRole.IDENTIFICATION
    override val startStep = "redirect"
    override val factorTypes = setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE, FactorType.INHERENCE)
    override val maxAcr = AcrLevel.LOA3
    override val claims = setOf(/* NAME, VORNAME, GEBURTSDATUM, Adresse (optional), Anker */)
}
```

`maxAcr` ist die Obergrenze. Das **erreichte** Niveau meldet jeder Durchlauf selbst, abhängig vom
Verfahren, das der Nutzer auf der Sprungseite gewählt hat:

| Verfahren | amr | factorTypes des Laufs | achievedAcr (Vorschlag) |
|---|---|---|---|
| eID (Karte + PIN) | `nect-eid` | Besitz, Wissen | loa3 |
| ePass (NFC-Chip + Selfie) | `nect-epass` | Besitz, Inhärenz | loa2 |
| EUDI-Wallet (PID) | `nect-eudi` | Besitz, Wissen (Geräteentsperrung) | loa3 |

### Schritte

| step | Wer | Was |
|---|---|---|
| `redirect` | App | `stepData` `nect-redirect` (`jumpUrl`, `caseId`); der Knopf „Weiter zu Nect“ leitet weiter; nach dem Rücksprung folgt `PATCH {caseId}` ohne weiteren Klick |
| – | Backend | Ergebnis beim Fremdsystem holen, Claims ableiten, `Completed.Identified` oder `Failed` |

### Arbeitsdaten

`id_nect.ident_tool_session (tool_session_id, case_id, created_at)`. Der Schlüssel ist die
`tool_session_id` (KONVENTIONEN.md); aufgeräumt wird über den `ToolSessionSweeper` des Moduls. Die
Ausweisdaten selbst speichert `id_nect` nicht; sie gehen als Claims an das Konto.

---

## 6) Redirect und Rücksprung im App-Kanal

1. Der Schritt `redirect` des Tools liefert `jumpUrl = /nect/?case={caseId}`. Die `callback_uri`, die
   `id_nect` beim Anlegen des Vorgangs mitgibt, ist `/app/`; Nect hängt `?nectCaseId={caseId}` an.
2. Der Browser verlässt die Seite der App. `channelSessionId` (localStorage) und DPoP-Schlüssel
   (IndexedDB) bleiben dabei erhalten, genau wie beim Neuladen.
3. Beim Rücksprung setzt die App den Kanal wie gewohnt fort (`GET /channels/{id}`), liest
   `nectCaseId` aus der URL (wie `pairingCode`), bereinigt die URL und schickt
   `PATCH /tools/{toolSessionId}/ident-nect {caseId}`. Die `toolSessionId` kommt aus `next`.
4. Passt die `caseId` nicht zur Tool-Session, ist das ein `Failed`; ein fremder Vorgang lässt sich so
   nicht einlösen. Jeder Vorgang lässt sich nur einmal einlösen (Schutz vor Wiederholung im
   Fremdsystem).

Bricht der Nutzer auf der Sprungseite ab (oder scheitert die Identifizierung), kommt er ebenfalls nur
mit `?nectCaseId=…` zurück; erst das Einlösen zeigt den Ausgang. Das Tool meldet `Failed`, und die App
bietet „Erneut versuchen“ (`PATCH {retry: true}` legt einen neuen Vorgang an) oder „Anderes Verfahren“
an.

---

## 7) Mock-Fremdsystem `nect_mock`

### Backend

- Modul `nect_mock`, `allowedDependencies = []`, Schema `nect_mock` mit
  `ident_case (id, callback_uri, loa, status, procedure, result JSON, created_at, redeemed_at)`.
- `NectService` (für `id_nect`): `createCase(callbackUri, loa): Case(id, jumpUrl)`,
  `redeem(caseId): NectResult?`. Es liefert das Ergebnis genau einmal, danach `null`.
- HTTP-Schnittstelle `/mock-nect/*` (für die Sprungseite): `GET cases/{id}` (Status, verlangtes Niveau),
  `POST cases/{id}/result` (Verfahren + Attribute), `POST cases/{id}/cancel`. Wie `/mock-kobil` liegt sie
  außerhalb von `/orchestrator` und braucht keine Anmeldung, denn es ist das fremde System.

### Frontend: Sprungseite `/nect/`

Eine eigene Vite-Seite wie `/personenverzeichnis/`, erkennbar als fremdes System (eigenes Theme, Banner
„Simulierter Identifizierungsdienst“, eigenes Symbol im Tab). Aufbau:

1. **Einstieg:** „Identifizierung für *DPoP-Demo*“, verlangtes Niveau, Auswahl
   **Online-Ausweis (eID)** · **Reisepass (ePass)** · **EUDI-Wallet**; dazu „Abbrechen“.
2. **Ein Formular je Verfahren**, vorbelegbar aus den Demo-Personen des Personenverzeichnisses, wie
   heute im eID-Formular:

| Verfahren | Simulierte Schritte | Felder | Besonderheit |
|---|---|---|---|
| eID | „Karte an das Smartphone halten“ → PIN | Name, Vorname, Geburtsdatum, Adresse, Restricted Identifier; PIN (6-stellig) | wie `ident-eid`, aber im fremden System |
| ePass | Dokumentnummer + CAN eingeben → „Chip auslesen“ → Selfie-Abgleich (Button „Selfie stimmt überein“) | Name, Vorname, Geburtsdatum, Dokumentnummer, Ausstellerstaat, Ablaufdatum | **keine Adresse** – der Pass trägt keine; ein abgelaufener Pass lässt sich zum Scheitern wählen |
| EUDI-Wallet | QR-Code/„Wallet öffnen“ → Freigabe-Dialog | PID: `given_name`, `family_name`, `birthdate`, optional `address` (kein Pseudonym, siehe unten) | **selektive Offenlegung**: Häkchen je Attribut; nicht geteilte Attribute fehlen im Ergebnis (passt zu `ClaimedIdentity`: null = nicht bestätigt) |

3. **Abschluss:** „Identifizierung abschließen“ speichert das Ergebnis im Mock und leitet zur
   `callback_uri`; „Identifizierung fehlgeschlagen“ und „Abbrechen“ führen die Fehlerwege vor.

So zeigt die Demo drei Dinge, die bisher nicht sichtbar waren: dass man die Anwendung verlässt, dass
verschiedene Dokumente verschieden viel bestätigen (ePass ohne Adresse, Wallet nur das Freigegebene)
und dass das erreichte Niveau vom gewählten Verfahren abhängt.

---

### Welche Daten Nect liefern kann – und welche wir anfragen (2026-09-24)

Nect veröffentlicht keine Entwickler-Dokumentation und keine Feldliste. Laut
[Datenschutzhinweis](https://support.nect.com/de/privacy-policy/) übermittelt Nect je nach
Vertrag nur das Minimum („über 18“) oder „Name, Vorname, Adresse, Geburtsdatum und -ort,
Verifizierungs(teil)ergebnis“, gegebenenfalls mit Ausweiskopie und Selfie. Was Nect weitergeben
**kann**, ist also durch das Dokument selbst begrenzt:

| | eID ([§18 PAuswG](https://www.gesetze-im-internet.de/pauswg/__18.html), [AusweisApp-Zugriffsrechte](https://www.ausweisapp.bund.de/sdk/messages.html)) | ePass ([ICAO 9303](https://www.icao.int/publications/doc-series/doc-9303), DG1) | EUDI-Wallet ([PID-Rulebook](https://github.com/eu-digital-identity-wallet/eudi-doc-attestation-rulebooks-catalog/blob/main/rulebooks/pid/pid-rulebook.md), [deutsches PID-Rulebook](https://bmi.usercontent.opencode.de/eudi-wallet/eidas-2.0-architekturkonzept/content/ecosystem-architecture/PID/german-pid-rulebook/)) |
|---|---|---|---|
| Auswahl | je Zugriffsrecht | **keine** – DG1 wird ganz gelesen | je Attribut (selektive Offenlegung); der Nutzer darf ablehnen |
| Name, Vorname, Geburtsdatum | ✓ | ✓ (MRZ-transliteriert, z. B. `MUELLER`, ggf. gekürzt) | ✓ |
| Anschrift | ✓ `Street`/`ZipCode`/`City`/`Country` – Hausnummer steckt in `Street` ([TR-03130](https://www.bsi.bund.de/SharedDocs/Downloads/DE/BSI/Publikationen/TechnischeRichtlinien/TR03130/TR-03130_TR-eID-Server_Part1.pdf?__blob=publicationFile&v=6): `HEIDESTRASSE 17`) | **✗** | optional `address.street_address` („Straße und Hausnummer“), `postal_code`, `locality`, `country` |
| Anker | `Pseudonym` (Restricted ID) | Dokumentnummer + Ausstellerstaat | **✗** – die PID trägt kein Pseudonym gegenüber der vertrauenden Stelle |
| Sonst (fragen wir nicht an) | Geburtsname, Geburtsort, Staatsangehörigkeit, Doktorgrad, Dokumentart, Gültigkeit, Alters-/Wohnortbestätigung | Geschlecht, Staatsangehörigkeit, Ablaufdatum, Gesichtsbild (DG2, für den Selfie-Abgleich) | Geburtsort, Staatsangehörigkeit, Geburtsname, Geschlecht, Ausstellungsdaten |

`ident-nect` fragt dasselbe an wie `ident-eid`: Name, Vorname, Geburtsdatum (worauf `ident-kvnr`
abgleicht), Anschrift und den Anker des jeweiligen Dokuments (`NECT_REQUESTED`). Der Mock bildet
das so ab:

- `createCase(callbackUri, requested)` legt die Anfrage im Vorgang ab, die Sprungseite zeigt
  sie an (`GET /mock-nect/cases/{id}` → `requested`).
- `NectProcedure.deliverable` legt fest, was ein Dokument überhaupt liefern kann. Daten, die das
  gewählte Dokument nicht trägt (etwa eine Anschrift aus dem Pass), lehnt der Mock ab.
- Beim Einlösen gibt Nect nur „angefragt ∩ gelesen“ weiter. Der Pass-Chip wird ganz gelesen,
  weitergegeben wird aber nur das Angefragte.
- Das Pass-Ablaufdatum prüft Nect selbst und gibt es nicht weiter. Ein Wallet-Pseudonym gibt es
  nicht mehr.

Folgen für das übrige Modell (umgesetzt am 2026-09-24):

- Straße und Hausnummer kommen bei eID und PID als **ein** Feld. `AttributeType.STRASSE` ist
  deshalb die ganze Straßenzeile, `HAUSNUMMER` gibt es nicht mehr. Nur das Personenverzeichnis trennt beide
  (P-4) und setzt sie an seiner Schnittstelle zusammen (`PersonData.strassenzeile`), auch für die
  Spiegelung nach Keycloak.
- Der Pass liefert Namen in MRZ-Schreibweise (`MUELLER`), die eID in Großbuchstaben (`MÜLLER`).
  Das Personenverzeichnis vergleicht Namen daher in MRZ-Form (`MrzName`): Großschreibung, Ä→AE, ß→SS,
  Diakritika weg, Name und Vorname als ein Namensfeld, gekürzt auf die 39 Zeichen des Passes.
  Die Sprungseite liefert beim Pass die Namen in dieser Form.

## 8) Web-Kanal

Im Web-Kanal zeigt die Keycloak-Erweiterung die Schritte der Tools an. Eine Weiterleitung aus der
Anmeldeseite von Keycloak hinaus und zurück in dieselbe Anmeldung (`login-actions` von Keycloak,
gebunden an die Anmeldesitzung) ist eine eigene Aufgabe. **Vorschlag:** zunächst nur im App-Kanal, mit `ident-nect` in `demo.tool-defaults` für `KEYCLOAK`
gesperrt (ADR-32); der Web-Client bietet es mangels Renderer ohnehin nicht an. Das Web käme später
mit einem `WebToolRenderer`, der die Weiterleitung über eine Action-URL von Keycloak als
`callback_uri` zurückführt.

---

## 9) Echte Anbindung: über ext-ident oder direkt an Nect

`NectService` bekommt zwei Umsetzungen, die per Profil gewählt werden: die simulierte und eine echte.

| Kriterium | A: ext-ident als Dienst anbinden | B: ext-ident-Logik ins Modul holen, Nect direkt |
|---|---|---|
| Muster | Abhängigkeit zu einem Fremdsystem wie `kobil_mock`, seit ADR-31 üblich | ebenso; Nect ist das Fremdsystem |
| Sicherheit zwischen den Diensten | Order-JWT, Confirmation-JWT, PKCE, JWKS und eine Liste erlaubter Aussteller nötig | entfällt (etwa 40 % des Codes von ext-ident) |
| Betrieb | zwei Systeme, ein Netzwerkweg mehr | eine einzige Installation, Nect als einzige externe Stelle |
| Rückmeldung | Nect → ext-ident | Nect → Modulith (`PUT /callbacks/nect/{caseId}`, ohne Anmeldung; geschützt über eine nicht vorhersagbare `caseId` und eine Prüfung des Zustands, wie in ext-ident) |
| Abgleich mit TKeasy | in ext-ident | entfällt hier; Zuordnung über `ident-kvnr` (Abschnitt 4) |
| Aufwand | etwa 3–5 Tage | etwa 7–9 Tage (NectClient übernehmen, Vorgänge abbilden, Rückmeldung) |
| Andere Nutzer des Dienstes | ext-ident bleibt nutzbar | müssten eine eigene Anbindung bauen |

**Empfehlung:** Für die Demo zuerst der simulierte Dienst (Abschnitt 7): Er braucht keinen Zugang und
zeigt alle Wege. Für einen echten Test **A**, solange ext-ident noch andere Nutzer hat; **B**, wenn nur
dieses System Nect nutzt. Der Wechsel betrifft nur die Umsetzung von `NectService`.

Bei **A** gilt aus dem ersten Papier weiter die Option „PKCE auf dem Server“: Das Backend erzeugt
`code_verifier` und `code_challenge`, reicht die Order selbst ein und löst sie selbst ein. Das Frontend
sieht nur die `redirect_uri` und den Rücksprung. Offen sind dort: die Liste erlaubter Aussteller und
der Wert von `process` bei ext-ident, die Audience der Confirmation und das nur einmalige Einlösen.

---

## 10) Aufwand (Demo mit simuliertem Dienst)

| Paket | Aufwand |
|---|---|
| `nect_mock`: Schema, `NectService`, HTTP-Schnittstelle `/mock-nect` | 1 Tag |
| Sprungseite `/nect/` mit drei Formularen, Theme, Vorbelegung | 1,5–2 Tage |
| `id_nect`: Descriptor, Controller, Handler, Tool-Session, Aufräumen | 1 Tag |
| App: Formular des Tools für `redirect`/`waiting`, Rücksprung per Deep-Link | 0,5–1 Tag |
| Tests (Handler, Integration mit Rücksprung, simulierter Dienst) und Doku | 1 Tag |
| **Summe** | **etwa 5–6 Tage** |

---

## 11) Offene Fragen

1. Rolle bestätigt? (Abschnitt 4: Kartendaten + `ident-kvnr` statt eigener Zuordnung.)
2. Niveau je Verfahren (Abschnitt 5) fachlich richtig, insbesondere ePass?
3. Soll die Sprungseite im selben Tab laufen (Weiterleitung, näher an der Wirklichkeit) oder in einem
   neuen (bequemer in der Demo)? Vorschlag: im selben Tab.
4. Web-Kanal: Wann, und mit welchem Rücksprung aus Keycloak heraus?
5. Echte Anbindung A oder B? Das hängt davon ab, wer ext-ident sonst noch nutzt.

---

## 12) Umsetzung (2026-09-23)

- Module `id_nect` (Tool) und `nect_mock` (Fremdsystem, `allowedDependencies = ["texts"]`, also nur
  die Textbibliothek, ADR-33). Die Abhängigkeit `id_nect → nect_mock` ist deklariert wie
  `auth_kobil → kobil_mock`. Die App spricht nur mit dem Orchestrator, die Sprungseite nur mit
  `/mock-nect`. Das Ergebnis holt das Backend direkt über `NectIdent.redeem` ab (nur einmal und nur für
  den Vorgang der eigenen Tool-Session).
- `amr` je Verfahren: `nect-eid`, `nect-epass`, `nect-eudi` (Entscheidung von Rene). Weil `amr` damit
  nicht mehr gleich `method` ist, erkennt `DefaultAuthPolicy.reIdentCandidates` „in dieser Sitzung
  schon benutzt“ zusätzlich über die `toolId` hinter dem Nachweis (`amrSourceId`).
- Der Pass liefert keine Adresse, die Wallet nur die freigegebenen Felder; `EID_RESTRICTED_ID` nur
  beim eID-Verfahren (Nect-eigenes Pseudonym, nicht dasselbe wie bei `ident-eid`).
- Nur im App-Kanal, dort als erstes Verfahren zur Identifizierung einsortiert. Der Web-Kanal bietet es
  nicht an, weil die Keycloak-Erweiterung keinen Renderer dafür hat (der Client gibt selbst an, was er
  kann). Eine zusätzliche Sperre im Backend gibt es nicht.
- Die offenen Fragen 1–3 sind damit entschieden: Rolle wie in Abschnitt 4, Niveaus wie in der
  Tabelle, im selben Tab.
- Abweichung vom Entwurf: Statt einer Schnittstelle `NectService` ruft `id_nect` direkt die Klasse
  `nect_mock.NectIdent` (`createCase`/`redeem`/`caseView`), wie `auth_kobil → KobilSsms` (ADR-31).
  Für die echte Anbindung (Abschnitt 9) müsste man daraus erst eine Schnittstelle machen. Außerdem
  hat `nect_mock` nicht, wie oben entworfen, gar keine Abhängigkeit (`[]`), sondern eine auf `texts`.

