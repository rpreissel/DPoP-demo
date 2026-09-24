# ADR-8: Keycloak führt seine eigenen nativen Schritte selbst, statt alles zu delegieren oder über Identity-Brokering zu gehen

> **Stand 2026-09-23:** Der Orchestrator wird inzwischen an mehr Stellen aufgerufen als nur für
> `KC_SELECT_METHOD`, und die Prüfung des Passworts läuft zustandslos über ihn. Die Entscheidung gilt;
> siehe Nachtrag.

**Entscheidung** (umgesetzt): Im Web-Kanal führt Keycloak seine eigenen Anmeldeabläufe so aus, wie
er sie selbst konfiguriert (Conditional-LoA-Subflows, eigene Passwortanmeldung). Den Orchestrator
ruft er nur für die Schritte auf, die er selbst nicht kann, und zwar innerhalb einer laufenden,
gespeicherten `AuthJourney` ([`KC_SELECT_METHOD`](../journeys/kc-select-method.md)). Solange ein
solcher Ablauf läuft, bestimmt allein der Orchestrator ACR und AMR und fasst dabei die Nachweise
zusammen ([05-api.md](../05-api.md) Abschnitt 3).

**Erwogene Alternativen**:

- **Orchestrator als externer OIDC-Identity-Provider** (Identity Brokering: Der Browser wird zu einer
  eigenen Weboberfläche des Orchestrators weitergeleitet). Das verletzt direkt die Grundregel „Der
  Browser spricht nie mit dem Orchestrator“.
- **Die ganze Journey an den Orchestrator abgeben** (Keycloak zeigt jedes Formular über einen
  einzigen allgemeinen Authenticator an). Das ist in der Architektur sauber, verzichtet aber ganz auf
  die eingebauten Fähigkeiten von Keycloak (eigene Passwortanmeldung, OTP/TOTP, WebAuthn/Passkey,
  Anmeldung über soziale Netzwerke, Conditional-LoA). Genau wegen dieser Fähigkeiten ergibt eine
  Anbindung an Keycloak überhaupt Sinn.
- **Einzelne Tools zustandslos aufrufen, ohne Journey** (Keycloak führt den Ablauf selbst und ruft
  den Orchestrator nur für einzelne Verfahren auf, ohne dazugehörige `ChannelSession`). Ohne die
  gespeicherte Journey kann der Orchestrator mehrere eigene Tools in derselben Anmeldung nicht mehr
  zu einem gemeinsamen Nachweis zusammenfassen. Sinnvoll bleibt das für einzelne Aktionen außerhalb
  eines zusammenhängenden Ablaufs (eine „Required Action“ in Keycloak, eine Aktion in der
  Kontoverwaltung).

**Kosten**: Zwei Systeme führen Zustand, und ihre Sicht kann auseinanderlaufen. Das Risiko wird
dadurch begrenzt, dass sich ihre Zuständigkeiten nicht überschneiden: Keycloak entscheidet, OB und
WELCHES Niveau angefragt wird. Der Orchestrator entscheidet, WAS innerhalb dieser Stufe geschieht
und wie mehrere Nachweise zu einem gemeinsamen ACR zusammenkommen.

**Nachtrag (2026-09-23)**:
- *Weitere Einstiege.* Der Keycloak-Kanal nimmt neben `KC_SELECT_METHOD` auch `REGISTER` an
  (`KcChannelService.entryIntentFor`), und `MANAGE_AUTH_METHODS` läuft als Required Action mit
  eigener Journey (`OrchestratorManageMethodsRequiredAction`, [05-api.md](../05-api.md) Abschnitt 3).
  Beides sind Schritte, die Keycloak selbst nicht kann; der Grundsatz „nur dafür“ gilt weiter.
- *Passwort.* Das Passwort prüft Keycloak nicht mehr selbst. `OrchestratorStorageProvider`
  (UserStorage mit `federationLink`) ruft dafür zustandslos, ohne Kanal und ohne Journey,
  `MgmtPasswordController` auf. Das ist die oben verworfene Form „einzelne Tools zustandslos
  aufrufen“, hier bewusst mitten in der Anmeldung: Es wird kein Nachweis zusammengefasst, der
  Orchestrator dient nur als Speicher für das Credential. Das eigene Passwortformular von Keycloak und
  die Steuerung des Niveaus bleiben bei Keycloak.

---
