# Deutsch – Redaktion der Entwicklerformulierung

Die Vorlagen im Code sind von Entwicklern geschrieben: technisch, teils mit Umschrift (ue, oe), Fachjargon und
internen Begriffen. Schreibe daraus den Text, den Versicherte einer gesetzlichen Krankenkasse in einer Login-
und Registrierungs-App lesen.

- **Anrede**: „Sie“, freundlich und sachlich, keine Ausrufezeichen, keine Großschreibung zur Betonung
  („ANDERSARTIGES“ → „anderes“).
- **Kurz**: ein bis zwei Sätze; Wichtigstes zuerst; was der Nutzer jetzt tun kann, wenn es das gibt.
- **Umlaute und ß** statt Umschrift („ungueltig“ → „ungültig“).
- **Jargon ersetzen**:
  - Enrollment / Enrollment-Referenz → Anmeldeverfahren / eingerichtetes Verfahren
  - Account → Konto; Channel / Kanal-Sitzung → Sitzung; Journey / Ablauf → Vorgang
  - Binding / Bindung (Gerät) → Verknüpfung mit dem Gerät
  - loa1/loa2/loa3, acr → Sicherheitsniveau (Platzhalterwerte bleiben unverändert)
  - intent, toolId, nativeToolId, Tool → „Vorgang“ bzw. „Verfahren“; technische Bezeichner nicht erklären
  - Retry-Limit → Zahl der Versuche
- **Feste Begriffe** (nicht umschreiben): Versichertennummer (KVNR), Freischaltcode, Online-Ausweis (eID),
  Reisepass, EUDI-Wallet, TAN, Passwort, E-Mail-Code, QR-Code, KOBIL, Nect.
- **Faktor-Typen** (eigene Texte „Wissen“, „Besitz“, „Inhärenz“): „Wissen“, „Besitz“, „Biometrie“.
- **Technische Fehler** (Platzhalter `{detail}`, `{id}`, Referenzen): allgemein verständlich formulieren, das Detail
  in Klammern am Ende stehen lassen.
- **Fremdsysteme** (Bundles `nect`, `kobil`, `register`): Ton des jeweiligen Dienstes, knapp, ebenfalls „Sie“.
- Englische Vorlagen (einige Systemfehler) ebenfalls ins Deutsche bringen.
