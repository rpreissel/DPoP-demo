# Deutsch – Redaktion der Entwicklerformulierung

> **Oberste Regel: gutes, verständliches Deutsch.** Schreiben Sie so, wie eine freundliche Mitarbeiterin am
> Telefon es einer Versicherten erklären würde – nicht wie ein Entwickler oder eine Behörde. Jeder Text muss
> ohne Vorwissen über das System verständlich sein. Im Zweifel einfacher, kürzer, konkreter.

## Wörter, die nie in einem ausgelieferten Text stehen

Kunstwörter und interne Begriffe aus dem Code sind kein Deutsch. Immer umschreiben:

| Nicht | Sondern |
|---|---|
| Registerperson, Register-Person, Person im Register | Ihre Daten bei der Krankenkasse, Ihr Versichertendatensatz, die versicherte Person |
| Registerperson zuordnen / Konto der Registerperson zuordnen | Konto mit Ihren Versichertendaten verbinden |
| Enrollment, enrollen | Anmeldeverfahren, einrichten |
| Channel, Tool, Step, Intent | Sitzung, Verfahren, Schritt, Anliegen |
| Binding, binden | Verknüpfung, verknüpfen |
| Login, einloggen | Anmeldung, anmelden |
| Authentifizierung, authentifizieren | Anmeldung, anmelden |
| Faktor, Faktor-Typ | Art des Verfahrens (Wissen, Besitz, Biometrie) |
| loa1, loa2, ACR, Trust-Level | Sicherheitsniveau (Werte in Platzhaltern bleiben stehen) |
| gedeckelt, Deckelung | begrenzt |

## Feste Demo-Begriffe – bleiben stehen

Diese Wörter sind eingeführte Begriffe der Demo (Doku, Diagramme, Oberfläche) und werden **nicht** ersetzt:

- **Journey**, **Journey-Log** (nicht „Vorgang“, „Vorgangsprotokoll“)
- **Versicherter**, **Partner** und **Interessent** – die drei Rollen eines Kontos: bei uns versichert
  (Versicherungsnummer), uns als Person bekannt (nur Partnernummer), keiner Person zugeordnet
- **Partnernummer** (`P` und neun Ziffern, jede Person im Personenverzeichnis hat eine)
- **Pairing-Code** (nicht „Kopplungscode“)
- **Realm** (Keycloak-Begriff, nicht „Bereich“) und **Keycloak** als Produktname
- **Personenverzeichnis** (das simulierte Fremdsystem mit den Personen, nicht „Versichertenregister“) und
  **Versicherungsnummer** (8 Ziffern, nur bei uns Versicherte – zusätzlich zur KVNR)

## Stil

- **Kurze Sätze**, eine Aussage pro Satz. Aktiv statt Passiv („Wir haben Ihnen … geschickt“ statt „Es wurde … gesendet“).
- **Verben statt Substantivketten** („Bitte bestätigen Sie Ihre E-Mail-Adresse“ statt „Bestätigung der E-Mail-Adresse erforderlich“).
- **Keine Anglizismen**, wo es ein gängiges deutsches Wort gibt. Etablierte Begriffe bleiben: E-Mail, SMS, App, QR-Code, PIN, TAN.
- Sagen, **was der Nutzer jetzt tun kann**, wenn etwas nicht geht.
- Auch Demo- und Admin-Texte (Welcome-Seite, Diagramme, Admin) in verständlichem Deutsch; nur Werte in
  Platzhaltern (Befehle, Pfade, IDs) bleiben technisch.

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

## Beschriftungen im Frontend und auf der Login-Seite

- **Buttons**: kurz, Verb im Infinitiv („Code senden“, „Weiter“), kein Satzzeichen.
- **Labels und Überschriften**: Substantiv bzw. knapper Titel, kein Satzzeichen („Telefonnummer“, „TAN eingeben“).
- **Platzhalter in Eingabefeldern**: Beispiel oder Format, kein ganzer Satz.
- **Demo-Hinweise** (Texte mit „Demo“): dürfen technisch bleiben, sie richten sich an Tester.
- Festbegriffe wie „TAN“, „SMS“, „QR-Code“ bleiben unverändert.
