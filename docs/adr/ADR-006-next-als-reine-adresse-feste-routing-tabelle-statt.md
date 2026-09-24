# ADR-6: `next` als reine Adresse, feste Routing-Tabelle statt HATEOAS

**Entscheidung**: Jede Antwort der API enthält ein Objekt `next`. Es nennt nur die Adresse des
nächsten Schritts (Typ, `toolId` oder `context`, Schritt) und liefert nie Inhalte oder Links mit.
Der Client findet den nächsten Schritt über eine **eigene, feste Routing-Tabelle im Client**
(`(toolId|context, step)` → Komponente der Oberfläche bzw. Endpunkt; siehe
[API](../05-api.md) Abschnitt 1 und [Frontend](../10-frontend.md)).

**Erwogene Alternative**: HATEOAS: Die Antwort liefert fertige, anklickbare Links (`href`).

**Warum diese**: Es gibt nur wenige mögliche nächste Schritte, und sie ändern sich selten. Das
Frontend braucht für jeden neuen Schritt ohnehin eine eigene Komponente; ein Link allein reicht nie.
Eine feste Tabelle zeigt außerdem, welche Übergänge das Frontend überhaupt kennt.

**Kosten**: Backend und Frontend müssen zueinander passen. Ein neuer Wert für `next` ohne passenden
Eintrag in der Routing-Tabelle des Frontends führt im Client zu einem Zustand, den er nicht behandelt.

---
