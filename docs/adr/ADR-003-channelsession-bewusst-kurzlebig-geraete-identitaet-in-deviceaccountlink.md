# ADR-3: `ChannelSession` bewusst kurzlebig, Geräte-Identität in `DeviceAccountLink`

**Entscheidung**: `ChannelSession` hat eine begrenzte Aufbewahrungsfrist (30 Tage, [Betrieb](07-betrieb.md)) und
trägt keine langlebige Geräte-Zuordnung. Die einzige dauerhafte Zuordnung Gerät -> Account
(`bindingKeyRef -> accountId`) liegt in `DeviceAccountLink`, einer eigenen Tabelle
([Domänenmodell](02-domaenenmodell.md) Abschnitt 1,
[DPoP-Bindung](09-dpop.md) Abschnitt 3).

**Erwogene Alternative**: `ChannelSession` selbst langlebig machen und die
Geräte-Wiedererkennung darüber lösen — ein wiederkehrendes Gerät würde dieselbe Session
fortsetzen.

**Warum diese**: Eine Session, die ein Gerät über Wochen repräsentiert, vermischt zwei
Lebensdauern in einer Entity: den Kanal-Vorgang (Stunden) und die
Geräte-Identität (dauerhaft). Der `bindingKeyRef` beweist
nur, welches Gerät spricht, nie, welche Session fortzusetzen ist; eine wiederkehrende
`ChannelSession` wird deshalb **immer** neu angelegt und nur mit `accountId` vorbefüllt.

**Kosten**: Zwei Konzepte statt eines — die Geräte-Bindung muss explizit über
`DeviceAccountLink` nachgeschlagen werden.

---
