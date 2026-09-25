# ADR-37: Bei einem nie identifizierten Konto genügt das Postfach auch für destruktive Aktionen

**Status**: entschieden (2026-09-25). Review 2026-09, M-7.

**Entscheidung**: Für ein Konto ohne Personenbindung (`personId == null`, etwa aus der Registrierung
„Enrollment zuerst“) genügt loa1 für Selbstbedienung: Konto löschen, Verfahren einrichten oder
entfernen, die E-Mail-Adresse zurückziehen (`selfServiceAcrFloor`). loa1 kann dabei allein der Code
an die E-Mail-Adresse sein. Wer das Postfach übernimmt, kann all das ohne zweiten Faktor. Jedes
identifizierte Konto verlangt weiterhin loa2.

**Warum**: Ein unidentifiziertes Konto trägt keine Identität und keine Stammdaten, die eine
Übernahme erreichen könnte. Seine E-Mail-Adresse ist sein Anker ([ADR-19](ADR-019-aufloesung-nur-ueber-anker-die-eid-restricted-id.md)):
Wer das Postfach hat, ist für dieses Konto faktisch der Inhaber – auch ein zweiter Faktor würde über
dasselbe Postfach zurückgesetzt. Mehr zu verlangen, als ein solches Konto erreichen kann, sperrt
nur aus: Ein nie identifiziertes Konto kommt ohne Identifizierung nie über loa1
(`DefaultAuthPolicy`, Deckel über `enrolledUnderAcr`).

Das folgt derselben Linie wie M-6: Wer mehr Sicherheit an seinem Konto will, identifiziert sich; die
Identifizierung ist der Weg nach oben, nicht ein zweiter Faktor an einem Konto ohne Identität.

**Erwogene Alternativen**:

- **Zwei verschiedene Faktorarten** (loa2 aus Kombination) für destruktive Aktionen: verworfen.
  Konten, die nur die E-Mail haben, könnten sich dann nicht mehr selbst löschen, ohne sich erst zu
  identifizieren – für ein Konto ohne Identität eine unverhältnismäßige Hürde.
- **Ein frischer Faktor, der nicht über die E-Mail läuft**: verworfen aus demselben Grund.

**Restrisiko**: Die Übernahme eines Postfachs bedeutet die Übernahme jedes unidentifizierten Kontos
an dieser Adresse, bis hin zu dessen Löschung. Geschützt ist dabei nichts, was einer Person zugeordnet
wäre; ein Konto mit Personenbindung erreicht der Weg nicht.
