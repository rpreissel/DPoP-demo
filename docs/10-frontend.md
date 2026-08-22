# Frontend

Anforderungen an die Demo-Oberfläche und die Regel, nach der sie navigiert.

Die zugrundeliegende API beschreibt [05-api.md](05-api.md), die Schlüsselerzeugung
[09-dpop.md](09-dpop.md).

---

## 1) Technische Anforderungen

| ID | Anforderung | Kriterium |
|----|-------------|-----------|
| FE-1 | Frontend auf Basis von React (aktuelle Version) und TypeScript. | siehe Versionstabelle in [08-projektrahmen.md](08-projektrahmen.md) |
| FE-2 | Das Frontend kann autark betrieben werden. | `npm run dev` startet den Vite-Dev-Server |
| FE-3 | Das Frontend kann über Spring Boot gehostet werden. | Build-Output landet in `src/main/resources/static`; `./gradlew bootRun` liefert es aus |
| FE-4 | Im Entwicklungsmodus werden API-Requests weitergeleitet. | Vite-Dev-Server proxyt `/orchestrator` nach `http://localhost:8080` |
| FE-5 | Das Frontend kommuniziert ausschließlich über den `orchestrator`. | Keine direkten Aufrufe an fachliche Module |

---

## 2) UI-Anforderungen

| ID | Anforderung | Kriterium |
|----|-------------|-----------|
| FE-6 | Übersichtliches Layout mit Karten, konsistentem Farbschema und Darkmode. | visuelle Gestaltung als Karten |
| FE-7 | Formulare sind mit Testdaten vorbelegt. | Identifikation und FSC-Eingabe direkt durchspielbar |
| FE-8 | Der aktuelle Stand und der nächste Schritt werden dargestellt. | Anzeige aus `next` und `stepData` |
| FE-9 | Telefonnummern werden clientseitig vorvalidiert. | Formatprüfung vor dem Absenden; das Backend lehnt ungültige Nummern mit `400` ab |
| FE-10 | Eine Reset-Aktion setzt den Demo-Zustand zurück. | Löscht den gespeicherten DPoP-Key, erzeugt einen neuen und startet den Flow neu |
| FE-11 | Nach erfolgreicher Anmeldung werden `accountId` und `personId` angezeigt. | Werte stammen aus dem `demo`-Objekt der Antwort (siehe unten) |

### Zum `demo`-Objekt

`accountId` und `personId` sind interne Korrelations-IDs und gehören fachlich nicht in eine
Client-Antwort — der Client braucht sie für keinen Folgeaufruf. Für die Demo-Oberfläche sind
sie dennoch nützlich, um den Ablauf nachvollziehbar zu machen. Sie werden deshalb in einem
eigens gekennzeichneten `demo`-Objekt geliefert, das kein Teil des produktiven Vertrags ist
und in einer echten Umgebung abgeschaltet wird ([05-api.md](05-api.md)).

---

## 3) Navigation ausschließlich über `next`

Das Frontend nutzt eine **feste lokale Routing-Tabelle** und trifft UI-Entscheidungen
ausschließlich anhand von `next` — nie anhand von URLs, Action-Namen oder eigener
Ableitung aus dem Sessionzustand.

- **Backend liefert**: `next.type` (`tool` oder `flow`), dazu `next.toolId` bzw. `next.context`, sowie `next.step`. Auswahloptionen stehen in `stepData.options`, fehlende Felder in `stepData.missingFields`.
- **Frontend entscheidet**: Aus diesen Angaben ermittelt eine lokale Routing-Tabelle (`routing.ts`), welche UI-Komponente anzuzeigen ist.
- **UI-Komponenten** sind an `(type, toolId|context, step)` gekoppelt, nicht an URL-Muster.
- Der Client konstruiert **niemals** eine `toolId` selbst; sie kommt entweder aus `next.toolId` oder als gewählter Eintrag aus `stepData.options`.

### Beispiel Routing-Tabelle

```
type = tool
  ident-fsc  / input     -> FscForm
  enroll-sms / enroll    -> SmsEnrollForm
  enroll-sms / tanInput  -> TanInputForm
  auth-sms   / auth      -> TanInputForm

type = flow
  registration   / selectIdentificationMethod -> IdentificationMethodSelection
  enrollment     / selectMethod               -> EnrollmentMethodSelection
  auth           / selectMethod               -> AuthenticationMethodSelection
  authentication / authenticated              -> AuthenticationCompleted
```

Bei `type = flow` und einem `selectMethod`-Schritt füllt das Frontend die Auswahl aus
`stepData.options`; die Einträge sind vollständige `toolId`-Werte und lassen sich direkt
auf den zugehörigen Endpunkt abbilden.

### Konsequenzen

- Alle Backend-URLs sind Implementierungsdetails und nicht Gegenstand der UI-Logik.
- Die UI-Navigation ist deterministisch und unabhängig von der Form der Backend-Endpunkte.
- Ein neues Tool erfordert im Frontend nur einen weiteren Eintrag in der Routing-Tabelle.
- Tools dürfen eigene Endpunkte mitbringen ([05-api.md](05-api.md), Tool-Namespace); auch die findet der Client über `(toolId, step)`, nicht über URL-Interpretation.
