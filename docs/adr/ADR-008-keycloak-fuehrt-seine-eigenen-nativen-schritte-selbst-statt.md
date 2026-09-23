# ADR-8: Keycloak führt seine eigenen nativen Schritte selbst, statt alles zu delegieren oder über Identity-Brokering zu gehen

> **Stand 2026-09-23:** Der Orchestrator wird inzwischen an mehr Stellen gerufen als nur für
> `KC_SELECT_METHOD`, und die Passwortprüfung läuft zustandslos über ihn. Die Entscheidung gilt;
> siehe Nachtrag.

**Entscheidung** (umgesetzt): Der Web-Kanal lässt Keycloak seine eigene, native
Authentifizierungs-Flow-Konfiguration (Conditional-LoA-Subflows, natives Passwort-Login) fahren
und ruft den Orchestrator nur innerhalb einer laufenden, persistenten `AuthJourney` für die
Schritte auf, die Keycloak nicht kann (`KC_SELECT_METHOD`, [04-orchestrierung.md](../04-orchestrierung.md)
Abschnitt 3). Der Orchestrator bleibt für die Dauer eines Flow-Durchlaufs alleinige, kombinierende
ACR/AMR-Instanz ([05-api.md](../05-api.md) Abschnitt 3).

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

**Nachtrag (2026-09-23)**:
- *Weitere Einstiege.* Der kc-Kanal nimmt neben `KC_SELECT_METHOD` auch `REGISTER` an
  (`KcChannelService.entryIntentFor`), und `MANAGE_AUTH_METHODS` läuft als Required Action mit
  eigener Journey (`OrchestratorManageMethodsRequiredAction`, [05-api.md](../05-api.md) Abschnitt 3).
  Beides sind Schritte, die Keycloak selbst nicht kann; der Grundsatz „nur dafür“ bleibt.
- *Passwort.* Das Passwort-Credential prüft Keycloak nicht mehr selbst: `OrchestratorStorageProvider`
  (UserStorage mit `federationLink`) ruft dafür zustandslos, ohne Kanal und Journey,
  `MgmtPasswordController` auf. Das ist die oben verworfene Form „zustandslose Einzel-Tool-Aufrufe“,
  hier bewusst im Login-Flow: Es wird kein Nachweis verrechnet, der Orchestrator ist nur der
  Credential-Speicher. Das native Passwort-Formular und die LoA-Steuerung bleiben bei Keycloak.

---
