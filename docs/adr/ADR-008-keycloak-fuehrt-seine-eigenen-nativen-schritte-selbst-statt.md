# ADR-8: Keycloak führt seine eigenen nativen Schritte selbst, statt alles zu delegieren oder über Identity-Brokering zu gehen

**Status**: umgesetzt.

**Entscheidung**: Im Web-Kanal führt Keycloak seine Anmeldeabläufe so aus, wie er sie selbst
konfiguriert (Conditional-LoA-Subflows, eigenes Passwortformular). Den Orchestrator ruft er nur für
Schritte auf, die er selbst nicht kann. Dafür gibt es drei Wege:

- **Innerhalb einer Anmeldung** mit einer gespeicherten `AuthJourney`: Der Einstieg ist
  [`KC_SELECT_METHOD`](../journeys/kc-select-method.md), bei einer Registrierung `REGISTER`
  (`KcChannelService.entryIntentFor`). Solange eine solche Journey läuft, bestimmt allein der
  Orchestrator ACR und AMR und fasst die Nachweise mehrerer Tools zusammen
  ([05-api.md](../05-api.md) Abschnitt 3).
- **Als Required Action** mit eigener Journey: Das Verwalten der Anmeldeverfahren
  (`MANAGE_AUTH_METHODS`) läuft über `OrchestratorManageMethodsRequiredAction`.
- **Zustandslos, ohne Kanal und Journey**, nur für die Prüfung des Passworts:
  `OrchestratorStorageProvider` (UserStorage mit `federationLink`) ruft dafür
  `MgmtPasswordController` auf. Hier wird kein Nachweis zusammengefasst; der Orchestrator dient nur
  als Speicher für das Credential. Das Passwortformular und die Steuerung des Niveaus bleiben bei
  Keycloak.

**Erwogene Alternativen**:

- **Orchestrator als externer OIDC-Identity-Provider** (Identity Brokering: Der Browser wird zu einer
  eigenen Weboberfläche des Orchestrators weitergeleitet). Das verletzt die Grundregel „Der Browser
  spricht nie mit dem Orchestrator“ ([ADR-7](ADR-007-web-kanal-ohne-mtls-signierte-request-assertion-statt.md)).
- **Die ganze Anmeldung an den Orchestrator abgeben** (Keycloak zeigt jedes Formular über einen
  einzigen allgemeinen Authenticator an). Das ist sauber, verzichtet aber ganz auf die eingebauten
  Fähigkeiten von Keycloak (Passwortanmeldung, OTP/TOTP, WebAuthn/Passkey, Anmeldung über soziale
  Netzwerke, Conditional-LoA). Genau wegen dieser Fähigkeiten ergibt die Anbindung an Keycloak Sinn.
- **Jeden Schritt zustandslos aufrufen, ganz ohne Journey**: Dann könnte der Orchestrator mehrere
  eigene Tools in derselben Anmeldung nicht mehr zu einem gemeinsamen Nachweis zusammenfassen. Deshalb
  gibt es den zustandslosen Aufruf nur dort, wo nichts zusammenzufassen ist: bei der Passwortprüfung.

**Begründung**: Keycloak bringt ausgereifte eigene Anmeldeverfahren mit; die sollen genutzt werden.
Der Orchestrator ergänzt nur, was Keycloak fehlt: die eigenen Verfahren der Demo und das
Zusammenfassen mehrerer Nachweise zu einem Niveau.

**Folgen und Kosten**: Zwei Systeme führen Zustand, und ihre Sicht kann auseinanderlaufen. Das Risiko
ist begrenzt, weil sich die Zuständigkeiten nicht überschneiden: Keycloak entscheidet, ob und welches
Niveau angefragt wird. Der Orchestrator entscheidet, was innerhalb dieser Stufe geschieht und wie
mehrere Nachweise zu einem gemeinsamen ACR werden.

**Geschichte**: Anfangs rief Keycloak den Orchestrator nur über `KC_SELECT_METHOD` auf und prüfte das
Passwort selbst. Der Einstieg `REGISTER`, die Required Action für das Verwalten der Verfahren und die
Passwortprüfung über den Orchestrator kamen später dazu (Stand 2026-09-23).
