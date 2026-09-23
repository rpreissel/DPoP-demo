# ADR-6: `next` als reine Adresse, feste Routing-Tabelle statt HATEOAS

**Entscheidung**: Jede API-Antwort enthält ein `next`-Objekt, das ausschließlich adressiert (Typ,
`toolId`/`context`, Step) und nie Inhalt oder Links mitliefert. Der Client bildet daraus über eine
**eigene, lokale, feste Routing-Tabelle** (`(toolId|context, step)` -> UI-Komponente bzw.
Endpunkt) den nächsten Schritt ab
([API](../05-api.md) Abschnitt 1, [Frontend](../10-frontend.md)).

**Erwogene Alternative**: HATEOAS — die Antwort liefert fertige, klickbare Links (`href`).

**Warum diese**: Die Menge möglicher nächster Schritte ist klein und stabil, und
das Frontend braucht für jeden neuen Schritt ohnehin eine eigene UI-Komponente, ein Link
allein reicht nie. Eine feste Tabelle macht zusätzlich sichtbar, welche Übergänge das
Frontend überhaupt kennt.

**Kosten**: Backend und Frontend müssen synchron gehalten werden — ein neuer `next`-Wert ohne
passenden Eintrag in der Frontend-Routing-Tabelle führt zu einem unbehandelten Zustand im Client.

---
