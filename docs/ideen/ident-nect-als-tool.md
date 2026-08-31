# Idee: Externe Identifikation über Nect als Tool (`ident-nect`)

Status: **Konzept, nicht umgesetzt**. Untersucht, ob der bestehende `ext-ident`-Microservice
als Identifikations-Tool in die DPoP-demo-Orchestrierung eingebunden werden kann — temporär,
als Brücke zum produktiven Nect-Verfahren.

Gegenüberstellung mit der Variante „ext-ident auflösen und integrieren":
[ident-nect-integration-in-modulith.md](ident-nect-integration-in-modulith.md).

---

## 1) Ausgangslage

Der Microservice `ext-ident` implementiert eine vollständige Identifikation über den
Nect-Dienstleister. Der Ablauf dort:

1. **Order erzeugen** — ein aufrufendes Backend erstellt ein signiertes Order-JWT
   (`sub`, `challenge`, `data.callback_uri`, `data.process`, `data.loa`).
2. **Order einreichen** — `POST /public/v1/tkapp` an ext-ident mit Order + PKCE-Challenge;
   ext-ident validiert, legt einen `IdentCase` an und gibt eine `redirect_uri` zurück.
3. **Nect-Website** — der Client öffnet die `redirect_uri`; der Nutzer durchläuft die
   Nect-Identifikation (ePass, eID oder Video-Ident).
4. **Callback** — Nect meldet das Ergebnis asynchron an ext-ident
   (`PUT /protected/cases/{caseId}`).
5. **Einlösen** — der Client ruft `POST /public/v1/tkapp/{caseId}` mit `code_verifier`
   und `transaction_id` auf; ext-ident prüft PKCE, holt Ausweisdaten, gleicht mit TKeasy
   ab und stellt bei Erfolg ein signiertes Confirmation-JWT aus.
6. **Confirmation verarbeiten** — das aufrufende Backend validiert das Confirmation-JWT
   und verarbeitet das Ergebnis.

Das DPoP-demo-Projekt kennt bisher zwei `IDENTIFICATION`-Tools (`ident-fsc`, `ident-eid`),
die beide rein serverseitig ablaufen. `ident-nect` wäre das erste Tool mit einem
**externen Redirect** (der Nutzer verlässt temporär die Anwendung).

---

## 2) Kernidee

Ein neues Tool-Modul `id_nect` im DPoP-demo-Backend, das ext-ident als externen Service
einbindet. Die Besonderheit: der Ablauf verteilt sich auf Backend *und* Frontend, mit einem
Browser-Redirect als Zwischenschritt.

### Ablauf als Sequenz

```
Orchestrator          id_nect-Tool         Frontend           ext-ident        Nect
    │                     │                    │                  │              │
    ├─ aktiviert ─────────>                    │                  │              │
    │                     │                    │                  │              │
    │   ┌─────────────────┤                    │                  │              │
    │   │ Order-JWT        │                    │                  │              │
    │   │ erzeugen +       │                    │                  │              │
    │   │ PKCE generieren  │                    │                  │              │
    │   └─────────────────┤                    │                  │              │
    │                     │                    │                  │              │
    │   InProgress("submit", {order, code_challenge, ext_ident_url})            │
    │                     ├───────────────────>│                  │              │
    │                     │                    │                  │              │
    │                     │                    ├─ POST /tkapp ──>│              │
    │                     │                    │<── redirect_uri ─┤              │
    │                     │                    │                  │              │
    │                     │                    ├─ window.open ───────────────────>
    │                     │                    │                  │     Ident    │
    │                     │                    │                  │<── callback ─┤
    │                     │                    │<─────── redirect (callback_uri) ┤
    │                     │                    │                  │              │
    │                     │                    ├─ POST /tkapp/{id}>              │
    │                     │                    │<── confirmation ─┤              │
    │                     │                    │                  │              │
    │   PATCH {confirmation_jwt, transaction_id, case_id}        │              │
    │                     │<──────────────────┤                  │              │
    │                     │                    │                  │              │
    │   ┌─────────────────┤                    │                  │              │
    │   │ Confirmation     │                    │                  │              │
    │   │ validieren       │                    │                  │              │
    │   │ (Signatur, aud,  │                    │                  │              │
    │   │  challenge)      │                    │                  │              │
    │   └─────────────────┤                    │                  │              │
    │                     │                    │                  │              │
    │<─ Completed.Identified(personId, amr, achievedAcr=loa2)    │              │
    │                     │                    │                  │              │
```

### Schritte im Tool

| nextStep | Wer agiert | Was passiert |
|---|---|---|
| `submit` | **Frontend** | Order + PKCE an ext-ident senden, `redirect_uri` erhalten, Nect-Website öffnen |
| `pending` | **Frontend** | Wartet auf Rückkehr von Nect; zeigt Hinweis „Identifikation läuft" |
| `redeem` | **Frontend** | Nach Rückkehr: ext-ident mit `code_verifier` aufrufen, Confirmation empfangen |
| `confirm` | **Backend** | Confirmation-JWT validieren, `Completed.Identified` melden |

---

## 3) Tool-Descriptor

```kotlin
object IdentNectDescriptor : ToolDescriptor {
    override val toolId = "ident-nect"
    override val method = "nect"
    override val role = ToolRole.IDENTIFICATION
    override val factorTypes = setOf(FactorType.POSSESSION)  // ePass/eID
    override val maxAcr = Acr.LOA2
}
```

`maxAcr=loa2` — ext-ident bestätigt Besitz eines Ausweises; das konkrete Verfahren
(ePass vs. eID vs. Video-Ident) steckt im `amr`-Claim der Confirmation. Ein höheres
Niveau (`loa3` bei eID mit PIN) wäre denkbar, ist aber von der Confirmation-Auswertung
abhängig und hier bewusst konservativ angesetzt.

---

## 4) Backend-Modul `id_nect`

### Verantwortlichkeiten

1. **Order erzeugen** — beim Start der Tool-Session ein signiertes JWT erstellen
   (eigener Signaturschlüssel, analog zum e-pin-Backend in ext-ident).
2. **PKCE-Paar generieren** — `code_verifier` serverseitig speichern,
   `code_challenge` an den Client geben.
3. **Confirmation validieren** — JWT-Signatur gegen ext-ident-JWKS prüfen,
   `audience`, `challenge`, `exp` validieren.
4. **ToolOutcome ableiten** — aus `amr`/`state` der Confirmation ein
   `Completed.Identified` oder `Failed` erzeugen.

### Was das Backend *nicht* tut

- Keinen direkten HTTP-Aufruf an ext-ident (das macht das Frontend).
- Keine Nect-Anbindung (das bleibt bei ext-ident).
- Keine Ausweisdaten empfangen oder speichern (nur die Confirmation).

### Konfiguration

```yaml
id-nect:
  ext-ident-base-url: https://ext-ident-dev.apps.example.net
  ext-ident-jwks-uri: https://ext-ident-dev.apps.example.net/private/v1/jwks
  order-issuer: dpop-demo
  order-audience: EXT_IDENT
  callback-uri-template: "{frontendBaseUrl}/tools/ident-nect/callback"
  process: DPOP_DEMO_IDENTIFIKATION
  loa: SUBSTANTIAL
```

### Datenmodell (Tool-Session-Daten)

```kotlin
data class IdentNectToolData(
    val orderId: String,           // JWT-ID der Order
    val challenge: String,         // zufälliger Challenge-Wert
    val codeVerifier: String,      // PKCE code_verifier (serverseitig)
    val codeChallenge: String,     // PKCE code_challenge (an Client)
    val state: NectToolState       // CREATED, SUBMITTED, COMPLETED, FAILED
)
```

---

## 5) Frontend-Modul

### Besonderheit: externer Redirect

Im Gegensatz zu `ident-fsc`/`ident-eid` verlässt der Nutzer die Anwendung. Das Frontend
muss damit umgehen, dass der Browser-Kontext nach dem Redirect verloren geht.

### Ablauf im Frontend

1. **`submit`-Schritt empfangen** — `stepData` enthält `order`, `code_challenge`,
   `code_challenge_method`, `ext_ident_url`.
2. **Order an ext-ident senden** — `POST {ext_ident_url}/public/v1/tkapp` direkt
   aus dem Browser (CORS muss konfiguriert sein, alternativ: Backend-Proxy).
3. **`redirect_uri` speichern** — zusammen mit `code_verifier` (vom Backend über
   `stepData` geliefert? Nein — PKCE-Geheimnis bleibt serverseitig, s. u.).
4. **Nect-Website öffnen** — `window.location.href = redirect_uri` oder neues
   Fenster/Tab.
5. **Rückkehr abfangen** — Nect redirected zur `callback_uri` mit `caseId` und
   `transactionId` als Query-Parameter.
6. **Einlösen über Backend** — das Frontend schickt `caseId`/`transactionId` an
   das eigene Backend (`PATCH /tools/ident-nect`), das Backend ruft
   ext-ident mit dem serverseitig gespeicherten `code_verifier` auf.

### Variante: PKCE-Handling

**Option A — PKCE komplett serverseitig (empfohlen)**:
Das Backend generiert `code_verifier` und `code_challenge`. Das Frontend kennt nur die
`code_challenge` (für den `POST /tkapp`-Aufruf). Beim Einlösen ruft das Backend
ext-ident selbst auf (mit `code_verifier`) und gibt dem Frontend nur das Ergebnis.
→ Einfacher, sicherer, aber Backend braucht doch HTTP-Aufruf an ext-ident.

**Option B — PKCE im Frontend**:
Das Frontend generiert `code_verifier`/`code_challenge`, speichert `code_verifier`
in `sessionStorage`, ruft ext-ident direkt auf. → Näher am Original-Flow, aber
`code_verifier` im Browser exponiert und CORS-Abhängigkeit.

**Empfehlung: Option A** — das Backend kapselt die ext-ident-Kommunikation für den
Einlöse-Schritt. Das Frontend kommuniziert nur mit dem eigenen Backend, genau wie bei
allen anderen Tools. Nur der initiale `POST /tkapp` und der Nect-Redirect laufen
über das Frontend.

---

## 6) Überarbeiteter Ablauf (Option A)

Bei Option A verschiebt sich die Verantwortung:

| nextStep | Frontend | Backend |
|---|---|---|
| `redirect` | Empfängt `redirect_uri` aus `stepData`, öffnet Nect-Website | Hat Order an ext-ident gesendet, `redirect_uri` + `code_verifier` gespeichert |
| `callback` | Empfängt `caseId`/`transactionId` aus URL, sendet an Backend | Ruft ext-ident `/tkapp/{caseId}` mit `code_verifier` auf, validiert Confirmation |

Damit reduziert sich der Frontend-Anteil auf zwei Schritte:
1. Nutzer zu Nect weiterleiten (Link/Button).
2. Nach Rückkehr: `caseId`/`transactionId` an eigenes Backend melden.

### Sequenz (Option A)

```
Frontend              id_nect-Backend         ext-ident           Nect
    │                       │                     │                 │
    ├─ GET (Tool starten) ─>│                     │                 │
    │                       ├─ POST /tkapp ──────>│                 │
    │                       │<── redirect_uri ────┤                 │
    │                       │  (speichert          │                 │
    │                       │   code_verifier,     │                 │
    │                       │   caseId, txId)      │                 │
    │                       │                     │                 │
    │<── InProgress("redirect", {redirect_uri}) ──┤                 │
    │                       │                     │                 │
    ├── Browser-Redirect ──────────────────────────────────────────>│
    │                       │                     │    Ident        │
    │                       │                     │<── callback ────┤
    │<──────────────────────── redirect (callback_uri?caseId&txId) ─┤
    │                       │                     │                 │
    ├─ PATCH {caseId, txId}>│                     │                 │
    │                       ├─ POST /tkapp/{id} ─>│                 │
    │                       │  (code_verifier)    │                 │
    │                       │<── confirmation ────┤                 │
    │                       │                     │                 │
    │                       │  Confirmation        │                 │
    │                       │  validieren          │                 │
    │                       │                     │                 │
    │<── Completed.Identified ──────────────────────────────────────
    │                       │                     │                 │
```

---

## 7) Offene Fragen und Risiken

### Architektonische Fragen

1. **Backend-zu-Backend-Kommunikation**: Option A erfordert, dass das DPoP-demo-Backend
   ext-ident direkt aufruft (für Order-Einreichung und Einlösung). Das widerspricht dem
   bisherigen Muster, bei dem Tools rein intern arbeiten. Ist das akzeptabel als temporäre
   Lösung?

2. **CORS vs. Proxy**: Falls das Frontend ext-ident direkt aufrufen soll (Option B),
   muss ext-ident CORS für die DPoP-demo-Origin erlauben. Alternativ könnte das Backend
   als Proxy dienen — dann ist Option A ohnehin der natürliche Weg.

3. **Order-Signatur**: Das DPoP-demo-Backend braucht einen eigenen Signaturschlüssel, den
   ext-ident als vertrauenswürdigen Issuer akzeptiert (Whitelist). Auf den Test-Stages
   konfigurierbar, in Produktion ggf. aufwändiger.

4. **Callback-URI und Session-Wiederherstellung**: Nach dem Nect-Redirect muss das
   Frontend die laufende Tool-Session wiederfinden. Möglichkeiten:
   - `caseId` als Query-Parameter in der Callback-URI, Tool-Session darüber auflösen.
   - Session-Cookie überlebt den Redirect (wahrscheinlich, da same-origin Callback).
   - `transactionId` in der Callback-URI zur Zuordnung.

5. **Process-Wert**: ext-ident erwartet einen `process`-Wert in der Order. Muss ein
   neuer Wert (`DPOP_DEMO_IDENTIFIKATION`) registriert werden, oder kann ein bestehender
   verwendet werden?

### Sicherheitsfragen

6. **Challenge-Binding**: Die Challenge bindet die Confirmation an die ursprüngliche
   Order. Das DPoP-demo-Backend muss die Challenge generieren, speichern und in der
   Confirmation zurückprüfen — analog zum e-pin-Backend.

7. **Audience-Trennung**: Order-Audience (`EXT_IDENT`) und Confirmation-Audience
   (DPoP-demo-spezifisch) müssen korrekt konfiguriert sein.

8. **Replay-Schutz**: Jede Confirmation darf nur einmal eingelöst werden. Das Backend
   muss die `caseId`/`transactionId` nach erfolgreicher Verarbeitung als verbraucht
   markieren.

### Betriebliche Fragen

9. **Verfügbarkeit**: ext-ident muss erreichbar sein. Timeout- und Retry-Strategie für
   die Backend-zu-Backend-Aufrufe definieren.

10. **Testbarkeit**: ext-ident hat Devtools als Nect-Proxy auf den Teststages. Das
    DPoP-demo müsste auf dieselben Devtools-Instanzen zeigen oder eigene Mocks bereitstellen.

---

## 8) Bewertung

### Vorteile

- **Echte Identifikation** statt simulierter FSC/eID — sofort nutzbar, sobald ext-ident
  auf einer Test-Stage erreichbar ist.
- **Passt ins Tool-Muster**: `ToolDescriptor`, `ToolOutcome`, Orchestrator-Steuerung —
  alles wie bei bestehenden Tools. Nur der Redirect ist neu.
- **Temporär rückbaubar**: Das Modul ist isoliert; Entfernen erfordert nur Löschen des
  Moduls und des Frontend-Tools.

### Nachteile / Aufwände

- **Externer Redirect** ist ein neues Muster im Frontend, das es bisher nicht gibt.
  Erfordert Session-Wiederherstellung nach Browser-Navigation.
- **Backend-zu-Backend-Kopplung** an ext-ident (Netzwerk, Verfügbarkeit, Konfiguration).
- **Schlüsselmanagement**: Eigener Signaturschlüssel für Orders, ext-ident-JWKS für
  Confirmations.
- **ext-ident-Konfiguration**: Neuer Issuer muss dort freigeschaltet werden.

### Einschätzung

**Machbar als temporäre Lösung.** Der Aufwand liegt hauptsächlich in:
1. Dem neuen Redirect-Muster im Frontend (~1–2 Tage).
2. Der Backend-Anbindung an ext-ident inkl. JWT-Signatur/Validierung (~2–3 Tage).
3. Der Konfiguration auf ext-ident-Seite (Issuer-Whitelist, Process-Wert).

Die Tool-Architektur des DPoP-demo-Projekts trägt diesen Anwendungsfall — ein
`ident-nect`-Tool fügt sich nahtlos in den bestehenden Katalog ein. Die Orchestrierung
muss nicht angepasst werden: `ident-nect` wird wie `ident-fsc`/`ident-eid` als
`IDENTIFICATION`-Kandidat angeboten.

---

## 9) Nächste Schritte (falls Umsetzung gewünscht)

1. Klären, ob Option A (Backend-Proxy) oder Option B (Frontend-direkt) gewünscht ist.
2. Process-Wert und Issuer-Whitelist mit ext-ident-Team abstimmen.
3. Backend-Modul `id_nect` anlegen (Descriptor, Controller, Handler, JWT-Service).
4. Frontend-Tool `ident-nect` mit Redirect-Handling implementieren.
5. Auf einer Test-Stage gegen ext-ident-Devtools testen.
