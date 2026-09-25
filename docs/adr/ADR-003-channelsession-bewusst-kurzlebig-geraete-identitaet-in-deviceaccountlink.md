# ADR-3: `ChannelSession` bewusst kurzlebig, Geräte-Identität in `DeviceAccountLink`

**Entscheidung**: `ChannelSession` hat eine begrenzte Aufbewahrungsfrist (30 Tage, [Betrieb](../07-betrieb.md)) und
trägt keine langlebige Geräte-Zuordnung. Die einzige dauerhafte Zuordnung Gerät -> Account
(`bindingKeyRef -> accountId`) liegt in `DeviceAccountLink`, einer eigenen Tabelle
([Domänenmodell](../02-domaenenmodell.md) Abschnitt 1,
[DPoP-Bindung](../09-dpop.md) Abschnitt 3).

**Erwogene Alternative**: `ChannelSession` selbst langlebig machen und das Wiedererkennen des Geräts
darüber lösen. Ein wiederkehrendes Gerät würde dann dieselbe Sitzung fortsetzen.

**Warum diese**: Eine Sitzung, die ein Gerät über Wochen darstellt, vermischt zwei Lebensdauern in
einer Entität: die Nutzung eines Kanals (Stunden) und die Identität des Geräts (dauerhaft). Der
`bindingKeyRef` beweist nur, welches Gerät spricht, nie, welche Sitzung fortzusetzen ist. Kommt ein
Gerät wieder, wird deshalb **immer** eine neue `ChannelSession` angelegt und nur mit der
`accountId` vorbelegt.

**Kosten**: Zwei Konzepte statt eines: Die Verknüpfung des Geräts muss ausdrücklich in
`DeviceAccountLink` nachgeschlagen werden.

---
