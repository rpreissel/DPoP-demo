# ADR-8: Keycloak führt seine eigenen nativen Schritte selbst, statt alles zu delegieren oder über Identity-Brokering zu gehen

**Entscheidung** (umgesetzt): Der Web-Kanal lässt Keycloak seine eigene, native
Authentifizierungs-Flow-Konfiguration (Conditional-LoA-Subflows, natives Passwort-Login) fahren
und ruft den Orchestrator nur innerhalb einer laufenden, persistenten `AuthJourney` für die
Schritte auf, die Keycloak nicht kann (`KC_SELECT_METHOD`, [04-orchestrierung.md](04-orchestrierung.md)
Abschnitt 3). Der Orchestrator bleibt für die Dauer eines Flow-Durchlaufs alleinige, kombinierende
ACR/AMR-Instanz ([05-api.md](05-api.md) Abschnitt 3).

**Erwogene Alternativen**:

- **Orchestrator als externer OIDC-Identity-Provider** (Identity Brokering, Browser-Redirect zu
  einer eigenen Orchestrator-Web-UI): verletzt die
  Leitplanke "Browser spricht nie mit dem Orchestrator" direkt.
- **Volle Journey-Delegation** (Keycloak rendert jedes Formular über einen einzigen generischen
  Authenticator): architektonisch sauber, verzichtet aber komplett auf Keycloaks
  eingebaute Fähigkeiten (natives Passwort-Login, OTP/TOTP, WebAuthn/Passkey,
  Social-Login-Brokering, Conditional-LoA) — genau die Fähigkeiten, derentwegen
  eine Keycloak-Anbindung überhaupt Sinn ergibt.
- **Zustandslose Einzel-Tool-Aufrufe ohne Journey** (Keycloak führt die Journey weiter selbst, ruft den
  Orchestrator nur für isolierte Faktoren ohne begleitende `ChannelSession` auf):
  ohne die persistente Journey verliert der Orchestrator die
  Fähigkeit, mehrere eigene Tools im selben Login zu einem gemeinsamen Nachweis zu verrechnen. Bleibt
  sinnvoll für Touchpoints außerhalb eines zusammenhängenden Flows (eine
  Keycloak-„Required Action", eine Aktion in der Account-Konsole).

**Kosten**: Split-Brain-Risiko zwischen zwei Zustandshaltern — abgefedert dadurch, dass jede Seite
eine nicht überlappende Zuständigkeit trägt (Keycloak entscheidet OB und WELCHES ACR-Level
angefragt ist, der Orchestrator WAS innerhalb einer Stufe passiert und wie sich mehrere Nachweise
zu einem Gesamt-ACR kombinieren).

---
