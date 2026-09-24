# Idee: Identifikation über Nect (`ident-nect`)

Status: **Umgesetzt als Demo mit Mock** (2026-09-23), nur im App-Kanal; die echte Anbindung (Abschnitt 9) und der Web-Kanal (Abschnitt 8) sind offen. Abweichungen vom Konzept stehen in Abschnitt 12. Fasst die beiden früheren Papiere
„ext-ident als Tool anbinden“ und „ext-ident in den Modulith integrieren“ (2026-08-31) zusammen
und gleicht sie mit dem heutigen Code ab. Neu: ein **Mock-Fremdsystem** mit Jumppage und Masken
für eID, ePass und EUDI-Wallet, damit das Verfahren in der Demo ohne Nect-Zugang durchspielbar
ist.

---

## 1) Ausgangslage

Der Microservice `ext-ident` identifiziert Personen über den Dienstleister **Nect**:

1. Ein aufrufendes Backend erzeugt ein signiertes **Order-JWT** (`sub`, `challenge`,
   `data.callback_uri`, `data.process`, `data.loa`) und reicht es mit einer PKCE-Challenge ein
   (`POST /public/v1/tkapp`). ext-ident legt einen `IdentCase` an und gibt eine `redirect_uri`
   zurück.
2. Der Nutzer durchläuft auf der **Nect-Jumppage** das Verfahren (eID, ePass oder Video-Ident).
3. Nect meldet das Ergebnis asynchron an ext-ident (`PUT /protected/cases/{caseId}`) und leitet
   den Browser zur `callback_uri` zurück.
4. Das Backend löst ein (`POST /public/v1/tkapp/{caseId}` mit `code_verifier`), ext-ident holt
   die Ausweisdaten, gleicht sie mit TKeasy ab und stellt ein signiertes **Confirmation-JWT** aus.

`ext-ident` besteht aus `ext-ident-server` (~45 Klassen: Order-Validierung, Case-Lifecycle,
Nect-Anbindung, TKeasy-Abgleich, Confirmation, Terminierung), `nect-rest-client` (~15 Klassen)
und `ext-ident-devtools` (Nect-Mock mit Thymeleaf-UI für Teststages).

Im DPoP-demo gibt es zwei `IDENTIFICATION`-Tools (`ident-fsc`, `ident-eid`) und ein
Zuordnungs-Tool (`ident-kvnr`, `CORRELATION`). `ident-nect` wäre das erste Tool, bei dem der
Nutzer die Anwendung **per Redirect** verlässt.

---

## 2) Was sich seit den ersten Papieren geändert hat

| Damals | Heute | Folge für Nect |
|---|---|---|
| „Tools arbeiten rein intern; eine Backend-zu-Backend-Kante bricht das Muster“ | Fremdsysteme sind eigene Module mit direkter, deklarierter Kante: `auth_kobil → kobil_mock` (`KobilSsms`), `id_fsc → ext_stammdaten` (`Freischaltcodes`, ADR-31) | Der Architektur-Einwand gegen die Anbindung entfällt. Nect wird ein weiteres Fremdsystem: `nect_mock` in der Demo, echte Anbindung dahinter austauschbar |
| Tool liefert `Completed.Identified(personId, …)` | ADR-18: Wer nur Ausweisdaten bestätigt, löst keine Person auf (`ident-eid`); die Zuordnung übernimmt `ident-kvnr` | Abschnitt 4 entscheidet die Rolle neu |
| Nur das App-Frontend betrachtet | Web-Kanal-Tools rendert die Keycloak-Extension (`WebToolRenderer`); Sperre je Kanal (ADR-32) | Start nur im App-Kanal, Web später (Abschnitt 8) |
| „Session-Cookie überlebt den Redirect“ | Der App-Kanal hat keine Cookies: `channelSessionId` in localStorage, DPoP-Schlüssel in IndexedDB, Fortsetzen per `GET /channels/{id}`; Deep-Link-Muster `/app/?intent=confirm_peer_login&pairingCode=…` | Rücksprung als Deep-Link `/app/?tool=ident-nect&caseId=…` (Abschnitt 6) |
| Tabellen ohne Schema (`nect_ident_case`) | Ein Datenbankschema je Modul (ADR-16), Arbeitsdaten als `<modul>.<rolle>_tool_session` | `id_nect.ident_tool_session`, `nect_mock.ident_case` |
| Terminierung per `@Scheduled` + ShedLock | Einzelinstanz (`DeploymentTopologyCheck`), Tool-Arbeitsdaten räumt je Modul ein `ToolSessionSweeper` ab | Kein ShedLock; Sweeper wie bei den übrigen Tools |
| Descriptor mit `toolId`/`method`/`role`/`factorTypes`/`maxAcr` | Dazu `claims` (`ClaimDeclaration`), `startStep`; `Completed.Identified` trägt Claims und `auditDetails` | Descriptor in Abschnitt 5 entsprechend |

---

## 3) Zielbild

```
App-Kanal (Browser-Tab "Smartphone")          Nect-Mock (Browser-Tab bzw. Redirect)
        │                                                │
        │ 1. ident-nect aktivieren                      │
        ▼                                                │
  id_nect ──── NectService (Kante) ────► nect_mock      │
        │   createCase(callback, loa)      Case anlegen  │
        │◄──────────── jumpUrl ─────────────┘            │
        │                                                │
        │ 2. Redirect zur Jumppage  ─────────────────────►  /nect/?case=…
        │                                                │   Verfahren wählen:
        │                                                │   eID · ePass · EUDI-Wallet
        │                                                │   Maske ausfüllen, abschließen
        │ 3. Rücksprung  ◄───────────────────────────────┘   /app/?tool=ident-nect&caseId=…
        │
        │ 4. PATCH {caseId}  →  id_nect holt Ergebnis beim Fremdsystem
        ▼
  ToolOutcome.Completed.Identified (Kartendaten als Claims)  →  weiter wie bei ident-eid
```

- **`id_nect`** ist das Tool-Modul (Descriptor, Controller, Handler) nach dem Muster von
  `id_eid`. Es kennt Nect nur über eine schmale Schnittstelle `NectService`.
- **`nect_mock`** ist das simulierte Fremdsystem nach dem Muster von `kobil_mock`:
  `allowedDependencies = []`, eigenes Schema, zwei Gesichter – `NectService` für unser Backend und
  die HTTP-Fassade `/mock-nect/*` für die Jumppage.
- **Echte Anbindung** (Abschnitt 9) ersetzt später nur die Implementierung von `NectService`;
  Tool, Frontend-Rücksprung und Outcome bleiben gleich.

---

## 4) Rolle: Kartendaten bestätigen, nicht Person auflösen

Nect bestätigt, was auf dem Dokument steht. Eine KVNR oder Registerperson trägt kein Ausweis,
kein Pass und keine Wallet-PID. ext-ident gleicht zwar selbst mit TKeasy ab, weil ihm die Order
den `sub` schon nennt – in unserem Ablauf ist beim Identifizieren aber noch keine Person bekannt.

**Empfehlung: angleichen an `ident-eid`** (ADR-18/19): `ident-nect` meldet die bestätigten
Attribute als Claims mit `ClaimSource.of("ident-nect")`, keine `PERSON_ID`. Die Zuordnung zur
Registerperson läuft danach wie heute über `ident-kvnr`. Kein zweiter Weg für dieselbe Frage.

Die Alternative – `ident-nect` ruft selbst `PersonDirectory.matchesStammdaten` und meldet eine
`PERSON_ID` wie `ident-fsc` – wäre eine zweite Zuordnungslogik neben `ident-kvnr` und lohnt nur,
wenn Nect künftig mit bekannter KVNR gestartet wird (Re-Identifikation eines bekannten Kontos).

**Wiedererkennung:** `ident-eid` nutzt die `restrictedId` als lokalen Anker. Die Entsprechung je
Verfahren:

| Verfahren | Anker-Kandidat | Bemerkung |
|---|---|---|
| eID | Restricted Identifier (Pseudonym je Diensteanbieter) | wie `ident-eid` |
| ePass | Dokumentnummer + Ausstellerstaat | wechselt mit jedem neuen Pass – wie eine neue Karte (ADR-19) |
| EUDI-Wallet | – | die PID trägt kein Pseudonym gegenüber der vertrauenden Stelle (Abschnitt 7); Pseudonyme laufen im ARF getrennt über Passkeys |

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

`maxAcr` ist die Obergrenze; das **erreichte** Niveau meldet jeder Lauf selbst, abhängig vom
Verfahren, das der Nutzer auf der Jumppage gewählt hat:

| Verfahren | amr | factorTypes des Laufs | achievedAcr (Vorschlag) |
|---|---|---|---|
| eID (Karte + PIN) | `nect-eid` | Besitz, Wissen | loa3 |
| ePass (NFC-Chip + Selfie) | `nect-epass` | Besitz, Inhärenz | loa2 |
| EUDI-Wallet (PID) | `nect-eudi` | Besitz, Wissen (Geräteentsperrung) | loa3 |

### Schritte

| step | Wer | Was |
|---|---|---|
| `redirect` | App | `stepData` `nect-redirect` (`jumpUrl`, `caseId`); Button „Weiter zu Nect“ leitet weiter; nach dem Rücksprung `PATCH {caseId}` ohne weiteren Klick |
| – | Backend | Ergebnis beim Fremdsystem holen, Claims ableiten, `Completed.Identified` oder `Failed` |

### Arbeitsdaten

`id_nect.ident_tool_session (tool_session_id, case_id, created_at)` – Schlüssel ist die
`tool_session_id` (KONVENTIONEN.md), aufgeräumt vom `ToolSessionSweeper` des Moduls. Die
Ausweisdaten selbst speichert `id_nect` nicht; sie gehen als Claims an das Konto.

---

## 6) Redirect und Rücksprung im App-Kanal

1. Der Tool-Schritt `redirect` liefert `jumpUrl = /nect/?case={caseId}`. Die `callback_uri`, die
   `id_nect` beim Anlegen des Case mitgibt, ist `/app/`; Nect hängt `?nectCaseId={caseId}` an.
2. Der Browser verlässt die App-Seite. `channelSessionId` (localStorage) und DPoP-Schlüssel
   (IndexedDB) überleben das – genau wie beim Neuladen.
3. Beim Rücksprung setzt die App den Kanal wie gewohnt fort (`GET /channels/{id}`), liest
   `nectCaseId` aus der URL (wie `pairingCode`), bereinigt die URL und schickt
   `PATCH /tools/{toolSessionId}/ident-nect {caseId}`. Die `toolSessionId` kommt aus `next`.
4. Passt die `caseId` nicht zur Tool-Session, ist das ein `Failed` – ein fremder Case kann so
   nicht eingelöst werden. Jeder Case ist nur einmal einlösbar (Replay-Schutz im Fremdsystem).

Bricht der Nutzer auf der Jumppage ab (oder scheitert die Identifizierung), kommt er ebenfalls nur
mit `?nectCaseId=…` zurück; erst das Einlösen zeigt den Ausgang. Das Tool meldet `Failed`, die App
bietet „Erneut versuchen“ (`PATCH {retry: true}` legt einen neuen Case an) oder „Anderes Verfahren“.

---

## 7) Mock-Fremdsystem `nect_mock`

### Backend

- Modul `nect_mock`, `allowedDependencies = []`, Schema `nect_mock` mit
  `ident_case (id, callback_uri, loa, status, procedure, result JSON, created_at, redeemed_at)`.
- `NectService` (für `id_nect`): `createCase(callbackUri, loa): Case(id, jumpUrl)`,
  `redeem(caseId): NectResult?` – liefert das Ergebnis genau einmal, danach `null`.
- HTTP-Fassade `/mock-nect/*` (für die Jumppage): `GET cases/{id}` (Status, verlangtes Niveau),
  `POST cases/{id}/result` (Verfahren + Attribute), `POST cases/{id}/cancel`. Wie `/mock-kobil`
  außerhalb von `/orchestrator`, ohne Login: Es ist das fremde System.

### Frontend: Jumppage `/nect/`

Eigene Vite-Seite wie `/ext/`, sichtbar ein fremdes System (eigenes Theme, Banner „Simulierter
Identifizierungsdienst“, eigenes Tab-Icon). Aufbau:

1. **Einstieg:** „Identifizierung für *DPoP-Demo*“, verlangtes Niveau, Auswahl
   **Online-Ausweis (eID)** · **Reisepass (ePass)** · **EUDI-Wallet**; dazu „Abbrechen“.
2. **Maske je Verfahren** (vorbelegbar aus den Demo-Personen des Registers, wie heute im
   eID-Formular):

| Verfahren | Simulierte Schritte | Felder | Besonderheit |
|---|---|---|---|
| eID | „Karte an das Smartphone halten“ → PIN | Name, Vorname, Geburtsdatum, Adresse, Restricted Identifier; PIN (6-stellig) | wie `ident-eid`, aber im fremden System |
| ePass | Dokumentnummer + CAN eingeben → „Chip auslesen“ → Selfie-Abgleich (Button „Selfie stimmt überein“) | Name, Vorname, Geburtsdatum, Dokumentnummer, Ausstellerstaat, Ablaufdatum | **keine Adresse** – der Pass trägt keine; ein abgelaufener Pass lässt sich zum Scheitern wählen |
| EUDI-Wallet | QR-Code/„Wallet öffnen“ → Freigabe-Dialog | PID: `given_name`, `family_name`, `birthdate`, optional `address` (kein Pseudonym, siehe unten) | **selektive Offenlegung**: Häkchen je Attribut; nicht geteilte Attribute fehlen im Ergebnis (passt zu `ClaimedIdentity`: null = nicht bestätigt) |

3. **Abschluss:** „Identifizierung abschließen“ speichert das Ergebnis im Mock und leitet zur
   `callback_uri`; „Identifizierung fehlgeschlagen“ und „Abbrechen“ führen die Fehlerwege vor.

So zeigt die Demo drei Dinge, die heute nicht sichtbar sind: das Verlassen der Anwendung, dass
verschiedene Dokumente verschieden viel bestätigen (ePass ohne Adresse, Wallet nur das Geteilte)
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

Offen:

- Straße und Hausnummer kommen bei eID und PID als **ein** Feld; unser Modell trennt sie (eigenes
  Issue).
- Pass-Namen sind MRZ-transliteriert. Ein Registerabgleich mit Umlauten würde daran scheitern;
  der Mock bildet das nicht nach.

## 8) Web-Kanal

Im Web-Kanal rendert die Keycloak-Extension die Tool-Schritte. Ein Redirect aus der
Keycloak-Anmeldeseite heraus und zurück in denselben Login (Keycloak-`login-actions`, gebunden an
die Anmeldesitzung) ist ein eigenes Stück Arbeit. **Vorschlag:** zunächst nur App-Kanal –
`ident-nect` in `demo.tool-defaults` für `KEYCLOAK` gesperrt (ADR-32); der Web-Client bietet es
mangels Renderer ohnehin nicht an. Web später mit einem `WebToolRenderer`, der den Redirect über
eine Keycloak-Action-URL als `callback_uri` zurückführt.

---

## 9) Echte Anbindung: über ext-ident oder direkt an Nect

`NectService` bekommt zwei Implementierungen, per Profil gewählt: den Mock und eine echte.

| Kriterium | A: ext-ident als Dienst anbinden | B: ext-ident-Logik ins Modul holen, Nect direkt |
|---|---|---|
| Muster | Fremdsystem-Kante wie `kobil_mock` – seit ADR-31 etabliert | ebenso; Nect ist das Fremdsystem |
| Inter-Service-Sicherheit | Order-JWT, Confirmation-JWT, PKCE, JWKS, Issuer-Whitelist nötig | entfällt (~40 % des ext-ident-Codes) |
| Betrieb | zwei Systeme, ein Netzwerk-Hop mehr | ein Deployment, Nect als einzige externe Stelle |
| Callback | Nect → ext-ident | Nect → Modulith (`PUT /callbacks/nect/{caseId}`, ohne Login; Schutz über unvorhersagbare `caseId` + Zustandsprüfung, wie in ext-ident) |
| TKeasy-Abgleich | in ext-ident | entfällt hier – Zuordnung über `ident-kvnr` (Abschnitt 4) |
| Aufwand | ~3–5 Tage | ~7–9 Tage (NectClient portieren, Case-Domäne, Callback) |
| Andere Konsumenten | ext-ident bleibt nutzbar | müssten eigene Anbindung bauen |

**Empfehlung:** Für die Demo zuerst der Mock (Abschnitt 7) – er braucht keinen Zugang und zeigt
alle Wege. Für einen echten Test **A**, solange ext-ident andere Konsumenten hat; **B**, wenn nur
dieses System Nect nutzt. Der Wechsel betrifft nur die `NectService`-Implementierung.

Bei **A** gilt aus dem ersten Papier weiter: Option „PKCE serverseitig“ – das Backend erzeugt
`code_verifier`/`code_challenge`, reicht die Order selbst ein und löst selbst ein; das Frontend
sieht nur die `redirect_uri` und den Rücksprung. Offene Punkte dort: Issuer-Whitelist und
`process`-Wert bei ext-ident, Audience der Confirmation, Einmal-Einlösung.

---

## 10) Aufwand (Demo mit Mock)

| Paket | Aufwand |
|---|---|
| `nect_mock`: Schema, `NectService`, `/mock-nect`-Fassade | 1 Tag |
| Jumppage `/nect/` mit drei Masken, Theme, Vorbelegung | 1,5–2 Tage |
| `id_nect`: Descriptor, Controller, Handler, Tool-Session, Sweeper | 1 Tag |
| App: Tool-Formular `redirect`/`waiting`, Deep-Link-Rücksprung | 0,5–1 Tag |
| Tests (Handler, Integration inkl. Rücksprung, Mock) und Doku | 1 Tag |
| **Summe** | **~5–6 Tage** |

---

## 11) Offene Fragen

1. Rolle bestätigt? (Abschnitt 4: Kartendaten + `ident-kvnr` statt eigener Zuordnung.)
2. Niveau je Verfahren (Abschnitt 5) fachlich richtig, insbesondere ePass?
3. Soll die Jumppage im selben Tab laufen (Redirect, näher an der Realität) oder in einem neuen
   (bequemer in der Demo)? Vorschlag: selber Tab.
4. Web-Kanal: wann und mit welchem Rücksprung aus Keycloak heraus?
5. Echte Anbindung A oder B – hängt an den übrigen Konsumenten von ext-ident.

---

## 12) Umsetzung (2026-09-23)

- Module `id_nect` (Tool) und `nect_mock` (Fremdsystem, `allowedDependencies = []`), Kante
  `id_nect → nect_mock` deklariert wie `auth_kobil → kobil_mock`. Die App spricht nur mit dem
  Orchestrator; die Sprungseite nur mit `/mock-nect`; das Ergebnis holt das Backend direkt bei
  `NectIdent.redeem` ab (einmalig, nur für den Case der eigenen Tool-Session).
- amr je Verfahren: `nect-eid`, `nect-epass`, `nect-eudi` (Entscheidung Rene). Weil amr damit nicht
  mehr gleich `method` ist, erkennt `DefaultAuthPolicy.reIdentCandidates` „in dieser Sitzung schon
  benutzt“ zusätzlich über die `toolId` hinter der Evidence (`amrSourceId`).
- Der Pass liefert keine Adresse, die Wallet nur die freigegebenen Felder; `EID_RESTRICTED_ID` nur
  beim eID-Verfahren (Nect-eigenes Pseudonym, nicht dasselbe wie bei `ident-eid`).
- Nur App-Kanal: dort als erstes Identifikationsverfahren einsortiert. Der Web-Kanal bietet es nicht an, weil die
  keycloak-extension keinen Renderer dafür hat (der Client deklariert, was er kann) – keine
  zusätzliche Sperre im Backend.
- Offene Fragen 1–3 damit entschieden: Rolle wie Abschnitt 4, Niveaus wie Tabelle, selber Tab.

