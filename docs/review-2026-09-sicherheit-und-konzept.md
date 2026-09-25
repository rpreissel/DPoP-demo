# Review 2026-09: Sicherheit und konzeptionelle Schwächen

Stand: 2026-09-25, Commit `e9f8fd9`. Offenes Review – die Befunde sind noch nicht umgesetzt.
Nach Abarbeitung wandert dieses Dokument nach [archiv/](archiv/).

**Umfang:** Backend (Orchestrator, Verfahrens- und Identifikationsmodule, `account`,
`ext_personenverzeichnis`), Keycloak-Extension und -Migrationen, Frontend (ohne Demo-Oberfläche),
Konfiguration und Deployment (`compose.yml`, `openshift/`).

**Bewusst ausgeklammert:** `demo_seed`, `kobil_mock`, `nect_mock`, Demo-Oberfläche, Testdaten.
Ein als „demo-only“ markiertes Stück zählt trotzdem, wenn es auf dem Betriebspfad (Profil
`keycloak`, Variante `openshift`) aktiv ist.

**Methode:** Sechs parallele Teilreviews (DPoP/Gerätebindung, Orchestrator, Verfahrensmodule,
Keycloak, Frontend/Infrastruktur, Account-Modul); die schweren Befunde sind im Code nachvollzogen.
Befund S-1 ist zusätzlich mit einem temporären Integrationstest reproduziert.

Schweregrade: **hoch** – ausnutzbar oder bricht eine dokumentierte Sicherheitszusage;
**mittel** – ausnutzbar unter Zusatzbedingungen oder deutliche Schwächung;
**niedrig/konzeptionell** – Härtung, undeklarierter Sonderfall, Doku-Abweichung.

---

## 1. Hoch

### S-1 Abgeschlossene Tool-Schritte lassen sich wiederholen – auch nach dem Logout

> **Behoben (2026-09-25):** `JourneyService.isCurrent` verlangt eine laufende Journey
> (`lifecycle == STARTED`); `ToolControllerSupport.applyOutcome` lässt die ToolSession bei
> `Completed` sofort ablaufen; `resolveChannel` weist beendete Kanäle (`LOGGED_OUT`/`EXPIRED`) ab.
> Regressionstests in `CancelLogoutIntegrationTest`. Offen bleibt als zusätzliche Absicherung das
> Verbrauchen der Einmalcodes im Modul selbst.

- **Wo:**
  - `orchestrator/journey/JourneyService.kt:174` – `findById` prüft nur `isExpired`, nicht `lifecycle`.
  - `orchestrator/journey/JourneyService.kt:258` – `isCurrent` liest nur `state.active`; `finish()`,
    `consume()`, `cancel()` setzen `active` nie auf `null`.
  - `orchestrator/channel/ToolControllerSupport.kt:215-233` – `applyOutcome` expiriert die
    ToolSession bei `Completed` nicht (nur `leave` tut das; `TOOL_TTL` = 10 min).
  - `auth_sms/internal/authsmsuse/AuthSmsUseToolHandler.kt:64-77` – TAN wird nach Erfolg nicht verbraucht.
- **Reproduziert:** `auth-sms` mit TAN → `AUTHENTICATED` → `DELETE /channels/{id}` → `LOGGED_OUT`
  → derselbe PATCH mit derselben TAN → Kanal wieder `AUTHENTICATED`, mit neuem AuthEvidence und
  AuthContext.
- **Weitere Folgen:**
  - `enroll-password` auf einer bereits verbrauchten MANAGE-Journey legt eine zweite Instanz an; die
    Enrollment-Zeile der ersten bleibt verwaist.
  - Eine abgebrochene Lookup-Login-Journey lässt sich über ihre alte ToolSession zu Ende führen,
    während auf dem Kanal schon eine neue Journey läuft.
  - Die dokumentierten Invarianten „Journey ist verbraucht“ und „`LOGGED_OUT` ist endgültig“
    (02-domaenenmodell §3) gelten nur für `ChannelService.resumeChannel`, nicht für den Tool-Pfad.
- **Vorschlag (strukturell):**
  - `findRunning` nur für `STARTED`; `loadContext`/`resolveJourney` darauf umstellen.
  - `active = null` bei jedem endgültigen Übergang (Authenticated, Cancel, Abort, Logout).
  - ToolSession bei jedem endgültigen Outcome expirieren, analog zu `leave`.
  - Endgültige Kanalzustände in `resolveChannel` abweisen.
  - Einmalgeheimnisse (TAN, E-Mail-Code) nach erfolgreichem Abgleich invalidieren.

### S-2 Keycloak-Account-Sync übernimmt fremde Keycloak-User per E-Mail-Treffer

> **Behoben (2026-09-25):** Der Sync sucht den Spiegel zuerst über `orchestratorAccountId` und
> übernimmt einen User per E-Mail nur, wenn er keinem noch bestehenden anderen Konto gehört
> (`resolveMirror` in `KeycloakAdminClient.kt`, Unit-Test `KeycloakMirrorResolutionTest`); mehr als
> ein Träger desselben `orchestratorAccountId` wird abgelehnt – im Sync und in der Extension
> (`AccountUsers.findByAccountId`). `username`/`email`/`firstName`/`lastName` darf der Nutzer im
> User-Profile nur noch sehen. `KcChannelService` weist ein abweichendes Konto ab (zwischen
> `accountId` und RestoreData wie gegenüber dem schon gebundenen Kanal), Test in
> `KcChannelIntegrationTest`.
>
> **Gegen echtes Keycloak geprüft (2026-09-25):** Szenario nachgestellt (Keycloak-User von Konto 2
> trägt die Adresse von Konto 1) – der Sync übernimmt ihn nicht. Dabei gefunden und behoben: Der volle
> Abgleich brach am ersten Konflikt ab und erreichte so nie das Konto, dessen eigener Sync den Konflikt
> löst. Jetzt überspringt er Konflikte, wiederholt sie am Ende einmal und meldet die übrigen
> (`KeycloakSyncResult.conflicts`, Test `KeycloakAccountSyncServiceTest`).

- **Wo:**
  - `orchestrator/kc/KeycloakAdminClient.kt:107` –
    `findMirror = email?.let(::findUserIdByEmail) ?: findUserId(accountId)`: E-Mail zuerst, egal
    welchem Konto der User gehört.
  - `orchestrator/kc/KeycloakAdminClient.kt:300-321` – `writeUser` überschreibt
    `orchestratorAccountId`, `federationLink`, Stammdaten und `emailVerified`, ohne zu prüfen, ob
    der User schon einen *anderen* `orchestratorAccountId` trägt.
  - `keycloak-migrations/.../KcHelpers.kt:28-37` – `email` im User-Profile für Rolle `user`
    editierbar; kein `verifyEmail`, Account-Console aktiv.
- **Angriff:** Der Angreifer setzt in der Account-Console seine Keycloak-E-Mail auf die Adresse des
  Opfers, bevor dieses sie im Orchestrator bestätigt. Beim nächsten `AccountChanged` wird sein
  Keycloak-User zum Spiegel des Opferkontos; seine laufende SSO-Sitzung liefert Tokens mit dessen
  `orchestrator_account_id`, `kvnr`, `person_id`.
- **Verstärker:**
  - `orchestrator/channel/KcChannelService.kt:69` – `effectiveAccountId = accountId ?: restoreData?.accountId`
    ohne Abgleich beider Werte; der Kommentar bei `:131-134` verspricht einen Mismatch-Fehler, den
    der Code nicht hat.
  - `orchestratorAccountId` ist nicht eindeutig; Extension und Sync nehmen jeweils den ersten
    Treffer (`OrchestratorAuthenticator.java:230`, `AccountTokenGrantType.java:155`,
    `AccountPublicKeyResource.java:104`).
- **Vorschlag:** Einen User mit anderem `orchestratorAccountId` nie übernehmen (Konflikt loggen);
  `email` im User-Profile nur für `admin` editierbar (Quelle ist ohnehin der Orchestrator);
  `accountId ≠ restoreData.accountId` → 409; bei mehr als einem Treffer hart abbrechen.

### S-3 Ein Signaturschlüssel für drei Keycloak-Clients – der Orchestrator ist dauerhaft Master-Admin

> **Behoben (2026-09-25):** Jeder Keycloak-Client hat einen eigenen Schlüssel (`node_signing_key`
> je `keycloak-client-auth:<clientId>`) und ein eigenes JWKS
> (`.../kc/client-jwks/{clientId}/.well-known/jwks.json`); das alte gemeinsame Paar löscht
> `V19__node_signing_key_je_client.sql`. `orchestrator-migration` hat im Master-Realm nur noch
> `create-realm` und verwaltet damit nur das selbst angelegte Realm; beim Umstieg von `admin` trägt
> die Extension die Verwaltungsrollen vorhandener Realms einmalig nach. ADR-25 angepasst,
> Unit-Test `OrchestratorClientAssertionSignerTest`. Die Theme-Umschaltung nutzt weiter den
> Migrationsclient – jetzt nur noch mit Rechten auf das eigene Realm.

- **Wo:**
  - `orchestrator/kc/OrchestratorClientAssertionSigner.kt:38-68` – ein Schlüsselpaar
    (`KEYCLOAK_CLIENT_AUTH`) signiert für `orchestrator-admin`, `orchestrator-app-token` und
    `orchestrator-migration`.
  - `keycloak-extension/.../bootstrap/MigrationClientBootstrapFactory.java:110-116` –
    `setFullScopeAllowed(true)` und Master-Rolle `admin`, bei jedem Start erneut.
  - `orchestrator/kc/KeycloakRealmLoginTheme.kt:40-43` – nutzt den Master-Admin-Zugang zur Laufzeit.
  - Privater Schlüssel im Klartext in `orchestrator.node_signing_key`.
- **Problem:** Die in ADR-9/ADR-25 beschriebene Rechtetrennung („`orchestrator-app-token` hat
  keinerlei Rechte“) ist wirkungslos: Wer den einen Schlüssel hat (DB-Dump, Backup, H2-Konsole),
  meldet sich als `orchestrator-migration` an und verwaltet alle Realms inklusive Master.
- **Vorschlag:** Eigener `purpose`-Schlüssel je Client (die Spalte existiert); Migrationsclient nur
  `create-realm` plus Realm-Admin des Zielrealms, oder nach erfolgreicher Migration deaktivieren;
  Theme-Umschaltung über `orchestrator-admin` mit `manage-realm`.

### S-4 Trust-all-TLS JVM-weit, auch auf dem OpenShift-Pfad

> **Behoben (2026-09-25):** `KeycloakTlsConfig` ist entfernt. Die HTTP-Clients zu Keycloak kommen
> aus `KeycloakHttp`; nur sie vertrauen einem selbstsignierten Zertifikat, und nur wenn die
> Setup-Variante `trustSelfSignedCertificate: true` erklärt (Basis `false`, eingeschaltet für
> `host` und `compose`, nicht für `openshift`). Die JVM-Standards bleiben unberührt
> (`KeycloakHttpTest`); beim Einschalten warnt der Start im Log. Ein Start-Check gegen Trust-all bei
> nicht-lokalem Keycloak entfällt: Die Ausnahme ist jetzt auf die Keycloak-Verbindungen begrenzt und
> ausdrücklich je Variante erklärt.

- **Wo:** `orchestrator/kc/KeycloakTlsConfig.kt:31-54` (`SSLContext.setDefault(trustAll)`,
  Hostname-Prüfung aus, `jdk.internal.httpclient.disableHostnameVerification=true`, `@Profile("keycloak")`);
  `openshift/dpop-demo.yaml:136` setzt `SPRING_PROFILES_ACTIVE=keycloak`.
- **Problem:** Auf OpenShift spricht der Orchestrator Keycloak über `http://localhost:8081` an –
  Trust-all ist dort unnötig, gilt aber für jeden ausgehenden HTTPS-Aufruf der JVM: künftig KOBIL,
  Nect, SMS-Gateway und ein externes JWKS, also der Vertrauensanker der Peer-Auth. Die Trennung
  „nur lokal“ hängt am Profil, nicht an der Umgebung.
- **Vorschlag:** An einen expliziten Schalter im `keycloak-setup`-Parametersatz binden (nur
  Varianten `host`/`compose`), besser das selbstsignierte Zertifikat gezielt als Truststore laden;
  Start abbrechen, wenn Trust-all aktiv und `keycloakBaseUrl` nicht localhost ist (Muster
  `DeploymentTopologyCheck`).

### S-5 Orchestrator-Admin auf OpenShift mit `admin/admin` öffentlich erreichbar

- **Wo:** `src/main/resources/application.yml:74-76` (Default `admin/admin`, `{noop}`);
  `openshift/deploy.sh` erzeugt ein Zufallsgeheimnis nur für den Keycloak-Admin;
  `openshift/dpop-demo.yaml` setzt kein `DEMO_ADMIN_PASSWORD`.
- **Problem:** Die Orchestrator-Route ist nach außen offen; `/orchestrator/admin/**` (Konten
  löschen, Demo-Reset, Journey-Log über alle Konten) ist mit dem Standardzugang erreichbar.
- **Vorschlag:** Secret analog zum Keycloak-Admin anlegen und per `secretKeyRef` einhängen;
  Start-Check, der in der Variante `openshift` mit Default-Passwort abbricht.

### S-6 KVNR-Existenz-Orakel über `ident-kvnr`, ungedrosselt

> **Behoben (2026-09-25):** `ident-kvnr` fragt vor dem Ergebnis über
> `ToolEndpoint.matchesAttestedIdentity`, ob die Person zur schon bezeugten Identität passt (Antwort
> aus `JourneyActionExecutor`, dieselbe Regel wie dessen eigene Prüfung). Eine fremde KVNR endet
> wie eine unbekannte: gleicher Text, gleiches `Failed`, dazu `attemptedPersonId`, also mit
> Buchung auf die Ident-Drossel dieser Person. Die Prüfung im Executor bleibt als zweite
> Sicherung. Tests: `IdentKvnrToolHandlerTest`, `IdentEidAssignmentIntegrationTest` (beide Antworten
> gleich). Der Zähler je Konto für CORRELATION entfällt: Ohne unterscheidbare Antwort gibt es nichts
> mehr abzufragen. Strukturell geschlossen in Phase D (Schritt 20): `ToolOutcome.Failed.Identification`
> verlangt `attemptedPersonId` als Pflichtfeld.

- **Wo:** `id_kvnr/internal/IdentKvnrToolHandler.kt:59-61` (unbekannte KVNR → `Failed` ohne
  `attemptedPersonId`); `orchestrator/journey/JourneyActionExecutor.kt:144-146` (KVNR einer anderen
  Person → `IdentityConflictException`, 409).
- **Problem:** Die beiden Fälle antworten verschieden (200/`Failed` gegen 409). Der 409 rollt die
  Transaktion samt Drosselbuchung zurück, die ToolSession bleibt aktiv. Wer einmal identifiziert
  ist, kann damit beliebig viele KVNR auf Vergabe prüfen. Der Kommentar in `IdentKvnrToolHandler.kt:47-49`
  („answers exactly like …“) stimmt eine Ebene höher nicht mehr.
- **Vorschlag:** Mismatch als `Failed` mit `attemptedPersonId` und identischem Text zurückgeben;
  zusätzlich ein Zähler je Konto für CORRELATION.

### S-7 Self-Service-Widerruf gilt für jeden lokalen Anker, nicht nur EMAIL

> **Behoben (2026-09-25):** `AnchorRule.retractableByHolder` ist je Anker ausdrücklich erklärt
> (nur `EMAIL` = `true`). Der Widerruf durch den Inhaber läuft unter dem neuen
> `RetractionAnchor.ACCOUNT_HOLDER`, und `AccountService.retractAttribute` lehnt ihn für jeden anderen
> Anker selbst ab – unabhängig vom Aufrufer; `ChannelService` antwortet vorab mit 409.
> `retractAttribute` sperrt jetzt die Kontozeile (ADR-14) und kündigt `AccountChanged` an; der
> Keycloak-Sync leert die E-Mail eines Spiegels, wenn das Konto keine mehr hat
> (`KeycloakAdminClient.clearEmail`, gegen echtes Keycloak noch ungeprüft). Tests:
> `AttributeRulesTest`, `AccountServiceDbTest`, `ManageMethodsIntegrationTest`.

- **Wo:** `orchestrator/channel/ChannelService.kt:347` prüft nur `isLocalAnchor` – das sind
  PERSON_ID, VERSNR, EID_RESTRICTED_ID, NECT_RESTRICTED_ID und EMAIL (`tool_api/AttributeRules.kt:76-88`).
  Die OpenAPI-Beschreibung (`ChannelController.kt:395`) behauptet „today only … email“.
- **Problem:** `allowsReplacement = false` für PERSON_ID schützt nur den Ersetzungspfad; Widerruf
  plus Neubindung umgeht ihn. Nach dem Widerruf gilt das Konto als nie identifiziert:
  `selfServiceAcrFloor` fällt auf loa1, eine Korrelation zu einer anderen Person wird möglich,
  `applyDirectoryChange` findet das Konto nicht mehr.
- **Dazu:** `account/AccountService.kt:134-167` – `retractAttribute` feuert kein `AccountChanged`
  (der Keycloak-Spiegel behält E-Mail, `emailVerified`, `personId`) und sperrt die Kontozeile nicht
  (ADR-14).
- **Vorschlag:** Widerrufbarkeit als Eigenschaft der `AnchorRule` deklarieren (`userRetractable`
  nur für EMAIL) und im Account-Modul erzwingen; `lockForUpdate` und `announceChanged` im Widerruf.

### S-8 Freischaltcode: rund 40 Bit, ungesalzenes SHA-256, mehrfach verwendbar

> **Entschieden, nicht geändert (2026-09-25):** Der Code bleibt bis zum Ablauf wiederverwendbar,
> weil `ident-fsc` auch der Weg zur Re-Identifizierung ist und Einmalnutzung einen Brief je
> Verwaltungsvorgang kostete. Entscheidung, verworfene Alternativen und Restrisiko („gefundener
> Brief“) stehen in ADR-31. Hash und Länge sind nach ADR-35 Sache des Fremdsystems.

- **Wo:** `ext_personenverzeichnis/Freischaltcodes.kt:63` (8 Zeichen aus 31 ≈ 39,6 Bit),
  `ext_personenverzeichnis/internal/Freischaltcode.kt:17-23` (SHA-256 ohne Pepper, Begründung
  „nicht enumerierbar“), `:35-40` (`isValidAt` – bis Ablauf wiederverwendbar).
- **Problem:** 8,5·10¹¹ SHA-256-Berechnungen dauern auf einer GPU Minuten. Wer `code_hash` lesen
  kann, hat den Code – und damit eine loa2-Identifizierung samt Kontoübernahme. Die TANs werden
  aus genau diesem Grund per HMAC mit Pepper gespeichert.
- **Vorschlag:** HMAC unter `dpop.secrets.otp-pepper` wie bei TAN/E-Mail-Code, oder ≥ 12 Zeichen;
  Code nach erfolgreicher Nutzung verbrauchen.

---

## 2. Mittel

### M-1 Geräte-Faktor: zweiter Faktor clientbehauptet, Unabhängigkeit vom DPoP-Schlüssel nicht erzwungen

- **Wo:** `orchestrator/dpop/DeviceProofValidator.kt:138-146` (`userVerification` als String aus
  dem selbstsignierten JWT); `auth_device/internal/enrolldevice/EnrollDeviceToolHandler.kt:48-74`
  (Gerätethumbprint wird nie mit `bindingKeyRef` verglichen).
- **Problem:** loa2 aus einem einzigen JWT, dessen zweiten Faktor der Client frei wählt. Die
  Ausnahme ist in 04-orchestrierung #8 benannt, die fehlende Schlüsselunabhängigkeit nicht – 06-abläufe #5
  behauptet sie sogar. Eine manipulierte App nutzt denselben Schlüssel für DPoP und Gerät.
- **Vorschlag:** Beim Enrollment `thumbprint == bindingKeyRef` ablehnen; ohne Plattform-Attestation
  den Descriptor auf `{possession}` setzen und den Step-up über ein zweites Verfahren holen.

### M-2 QR-Login: Deep-Link umgeht den Prüfcode

> **Behoben (2026-09-25), anders als unten vorgeschlagen:** Den Prüfcode in der App eintippen zu
> lassen, hätte nicht geholfen – der Angreifer schreibt ihn einfach mit in die Nachricht. Umgesetzt
> ist ein **Code in Gegenrichtung**: Die Freigabe in der App meldet den Browser nicht mehr an, sondern
> erzeugt einen sechsstelligen Bestätigungscode (nur als Hash gespeichert, einmal an die App
> ausgeliefert), den man in den wartenden Browser tippt (`QrLoginBrowserSide`, Schritte `enterCode`
> im Browser und `showCode` in der App). Nach drei falschen Codes ist die Anfrage verbrannt. Alle
> Übergänge prüfen `expiresAt`. Der Vergleichscode entfällt. Angepasst: Keycloak-Seite (FreeMarker und
> Keycloakify), App-Frontend, OpenAPI-Snapshot, Doku 05/07/Journey. Tests:
> `AuthQrFlowIntegrationTest`, `ConfirmQrLoginToolHandlerTest`.

- **Wo:** `auth_qr/internal/confirmqrlogin/ConfirmQrLoginToolHandler.kt:38-44` (vorbefüllter
  Pairing-Code springt direkt nach `confirm`); `:116-119` (Prüfcode 000–999 nur angezeigt, nie eingegeben).
- **Angriff:** Angreifer startet `auth-qr-lookup`, schickt dem Opfer `…/app/?pairingCode=X` mit
  „Prüfcode 471“ → Opfer bestätigt → der Browser des Angreifers ist im Konto des Opfers (loa2).
  Das ist Phishing per Link, kein MitM – 07-betrieb #5 nimmt nur MitM aus.
- **Dazu:** `auth_qr/internal/QrLoginRequestRepository.kt:18-26` (`resolveIfPending`) und der
  `APPROVED`-Poll prüfen `expiresAt` nicht; eine nach 30 min bestätigte Anfrage meldet noch an.
- **Vorschlag:** Prüfcode in der App eingeben lassen; Deep-Link nur bis `input` durchreichen;
  `expiresAt` in beiden Pfaden prüfen.

### M-3 Ausgegebene Access-Tokens sind reine Bearer-Tokens

- **Wo:** `orchestrator/session/TokenService.kt:121-135`, `orchestrator/session/KcTokenProvider.kt:106-130`,
  `keycloak-extension/.../AccountTokenGrantType.java:140` – kein `cnf.jkt`, kein `ath`.
- **Problem:** Die DPoP-Bindung endet an `GET …/token`. Ein abgegriffenes Access-Token ist gegen
  jeden Resource-Server ohne Schlüssel nutzbar. docs/09 suggeriert eine durchgehende Bindung.
- **Vorschlag:** Keycloak-DPoP aktivieren und den Gerätethumbprint als `cnf.jkt` setzen, oder die
  Grenze in ADR-9 und docs/09 ausdrücklich benennen.

### M-4 Refresh-Ablauf wirkungslos, Sitzung 24 h ohne erneute Anmeldung; hartes Logout lässt Keycloak-Sitzung stehen

> **Behoben (2026-09-25):** Ein abgelaufenes oder von Keycloak abgelehntes RefreshToken beendet die
> Anmeldung (`SessionExpiredException` → Kanal `EXPIRED`, `410`), statt neu auszustellen. Im
> Standardprofil gleitet das Refresh-Fenster (30 Minuten Leerlauf); im Profil `keycloak` gelten
> Keycloaks Grenzen. Beide Abmeldewege laufen über `JourneyService.endSession` (Tokens verwerfen,
> Keycloak-Sitzung beenden). Ein eigener `lastAccessedAt`-Leerlauf ist damit nicht nötig. Tests in
> `TokenServiceTest`, `KcTokenProviderTest`, `CancelLogoutIntegrationTest`.

- **Wo:** `orchestrator/session/TokenService.kt:63-68`, `KcTokenProvider.kt:66-74` (bei abgelaufenem
  Refresh wird ohne Prüfung neu ausgestellt); `ChannelService.kt:1085` (`CHANNEL_TTL = 24h`);
  `lastAccessedAt` wird gepflegt, aber nie für einen Leerlauf-Ablauf genutzt.
- **Logout:** `ChannelService.logout` (`:304-317`) setzt nur `authContextId = null`; nur
  `Transition.Logout` publiziert `KeycloakSessionEnded`. Der Refresh-Token bleibt in `auth_context`.
- **Vorschlag:** Bei abgelaufenem Refresh erneute Anmeldung verlangen; Leerlauf-Ablauf über
  `lastAccessedAt`; beide Logout-Wege über eine gemeinsame Funktion, die den Refresh-Token löscht
  und die Keycloak-Sitzung beendet.

### M-5 Mehrere laufende Journeys pro Kanal möglich

> **Behoben (2026-09-25):** Vom modellbasierten Test (`ModelBasedJourneyTest`) ohne Vorwissen
> gefunden und auf sieben Schritte geschrumpft (Verwaltung, Abbruch, Verwaltung, Peer-Login).
> `JourneyService.start` bricht jetzt bei jeder neuen Journey auf oberster Ebene die noch laufende
> samt pausierter Eltern ab – wie `startLogout` es schon tat. Ein partieller Unique-Index geht mit
> H2 nicht; die Regel steht als I-3 im Invariantenregister.

- **Wo:** `orchestrator/journey/JourneyService.kt:104-120` – `start` prüft nie `findActive`;
  `startManage`, `startPeerLogin`, `startDeleteAccount`, `raiseRequiredAcr` prüfen nur
  `state == AUTHENTICATED`. Kein DB-Constraint.
- **Problem:** Das Versuchsbudget (3 je Journey) wird mit jeder weiteren Journey neu vergeben;
  Zustandsübergänge zweier Journeys überlagern sich (z. B. setzt das `finish()` einer MANAGE-Journey
  den Kanal mitten im DELETE-Ablauf auf `AUTHENTICATED`).
- **Vorschlag:** Guard in `start` (409 oder deterministisch abbrechen) plus partieller Unique-Index
  `(channel_session_id) WHERE lifecycle = 'STARTED'`.

### M-6 MANAGE darf ein identifiziertes Konto unter loa2-Erreichbarkeit reduzieren

> **Entschieden, nicht geändert (2026-09-25):** Keine Aussperrung, sondern ein gewollter Umweg:
> Unter `loa2` gefallen führt die Re-Identifizierung zurück. Eine strengere Prüfung hätte legitime
> Wünsche abgelehnt (Passwort entfernen, nur SMS behalten). Die zwei Schwellen meinen verschiedene
> Dinge; Begründung und Restrisiko in [journeys/manage-auth-methods.md](journeys/manage-auth-methods.md).

- **Wo:** `orchestrator/journey/JourneyActionExecutor.kt:589, 638` – Selbstaussperr-Prüfung gegen
  `acrFloorOf(channel)` (Default loa1); `RegisterStrategy.afterEnrollment` erzwingt dagegen loa2.
- **Problem:** Zwei Lesarten derselben Regel. Nach Entfernen einer Methode erreicht das Konto loa2
  nur noch per Re-Identifizierung; jede weitere Verwaltung hängt daran.
- **Vorschlag:** Gegen `max(acrFloorOf(channel), selfServiceAcrFloor(account))` prüfen und die
  Schwelle an einer Stelle definieren.

### M-7 E-Mail-Postfach genügt für destruktive Aktionen an nie identifizierten Konten

- **Wo:** `orchestrator/journey/IntentStrategy.kt:41-42` (`selfServiceAcrFloor` = loa1 ohne
  `personId`); `auth-email-lookup` mit maxAcr loa1.
- **Problem:** Wer ein Postfach übernimmt, kann das Konto löschen, die Adresse zurückziehen (nimmt
  das Passwort mit) und eigene Geräte einrichten – ohne zweiten Faktor. Folge der Entscheidung
  „E-Mail als Auth-Mittel“, aber für destruktive Aktionen nicht ausdrücklich abgewogen.
- **Dazu:** Der KDoc von `accountOfAttestation` (`JourneyActionExecutor.kt:323-342`) spricht von
  „same journey“, der Code prüft die Kanal-Evidenz.
- **Vorschlag:** Für Löschen/Methodenverwaltung unidentifizierter Konten einen zweiten Faktortyp
  oder einen frischen Nicht-E-Mail-Faktor verlangen – oder die Entscheidung als ADR festhalten.

### M-8 Keycloak: kein Brute-Force-Schutz; Mgmt-Passwort für jedes Konto setzbar; Sync hebt Sperren auf

> **Behoben (2026-09-25):** `MgmtPasswordController.verify` zählt auf die Kontosperre von
> `auth-password` (gesperrt → `false` bei gleichem Zeitaufwand); `set` ersetzt nur ein vorhandenes
> Passwort, sonst `409`; der Sync setzt `enabled` nur noch beim Anlegen; das Realm hat
> `bruteForceProtected` (5 Fehlversuche in 15 Minuten, Wartezeit bis 15 Minuten, nie dauerhaft).
> Tests in `MgmtPasswordIntegrationTest`. Realm-Einstellung gegen echtes Keycloak noch ungeprüft.

- **Wo:**
  - `keycloak-migrations/.../V1__realm.kc.kts:19-45` – `bruteForceProtected` nicht gesetzt;
    `OrchestratorStorageProvider.java:63-74` reicht jeden Versuch weiter; `MgmtPasswordController.verify`
    ohne Versuchszähler.
  - `orchestrator/api/v1/kc/MgmtPasswordController.kt:63-85` – `set` ohne Prüfung, ob das Konto
    eine Passwortmethode hat; erreichbar über Keycloaks Admin-API („reset password“) mit `manage-users`.
  - `KeycloakAdminClient.kt:302` – `enabled = true` bei jedem Sync.
- **Vorschlag:** `bruteForceProtected` im Realm; Zähler je `accountId` im Mgmt-Endpunkt; `set`
  nur bei vorhandener Passwortmethode; `enabled` nur beim Anlegen setzen.

### M-9 Hop Keycloak → Orchestrator unverschlüsselt, Antwort nicht authentisiert

- **Wo:** `application-keycloak.yml` (`orchestratorBaseUrl` überall `http://`);
  `OrchestratorAuthenticator.java:158-162` (`findOrCreateUser(response.authDataAccountId())`).
- **Problem:** Peer-Auth sichert nur die Anfrage. Wer auf diesem Hop mitlesen und antworten kann,
  bestimmt, wer eingeloggt wird. Im Compose-Netz relevant, im OpenShift-Pod (localhost) nicht.
  ADR-7 spricht von Verschlüsselung, die es nicht gibt.
- **Vorschlag:** TLS auf dem Hop oder signierte Antwort; mindestens die Netzannahme in ADR-7 festhalten.

### M-10 `resetOnSetupChange` baut das Realm beim Start ohne Rückfrage neu

- **Wo:** `keycloak-migrations/.../MigrationRunner.kt:154-197`; in OpenShift gehört
  `PUBLIC_ORCHESTRATOR_URL` zum `RealmSetup`.
- **Problem:** Weg sind Sitzungen, alle `sub`-IDs, Public-Key-Credentials und künftige native
  Credentials.
- **Vorschlag:** Neuaufbau nur mit explizitem Schalter; sonst Start mit klarer Meldung abbrechen.

### M-11 Klartext-TANs und -Codes samt Empfänger auf STDOUT

> **Behoben (2026-09-25):** Der Versand läuft über zwei simulierte Fremdsysteme, `sms_mock.SmsGateway`
> und `mail_mock.MailServer` – eigene Module nach dem Muster von `kobil_mock` (ADR-31), kein neuer
> Port in `tool_api`. Sie legen das Gesendete in einen Postausgang im Speicher und loggen nur die
> letzten Ziffern der Nummer bzw. die Domain, nie den Code. Die Tests lesen den Code aus dem
> Postausgang statt aus STDOUT. Eine ArchUnit-Regel verbietet `println` und `System.out/err` im
> ganzen Backend (außer dem Migrations-Runner).

- **Wo:** `println("[MOCK SMS] TAN $tan an $phoneNumber …")` und Gegenstücke in
  `AuthEmailLookupToolHandler.kt:120`, `AuthEmailUseToolHandler.kt:94`, `ConfirmEmailToolHandler.kt:133`,
  `AuthSmsLookupToolHandler.kt:116`, `AuthSmsUseToolHandler.kt:95`, `EnrollSmsToolHandler.kt:121`.
- **Problem:** `DemoDisclosure` ist als einzige, abschaltbare Stelle für Klartext gebaut; diese
  sechs Stellen umgehen sie. Auch mit `demo.disclosure=false` stehen Code und Empfänger im Container-Log.
- **Vorschlag:** Versand hinter Ports (`SmsGateway`, `MailGateway`) mit Mock-Implementierung;
  Architekturtest „kein `println` in Methodenmodulen“.

### M-12 Keycloak im Dev-Modus auf OpenShift; H2-Konsole für den Nachbar-Container erreichbar

- **Wo:** `openshift/dpop-demo.yaml:90` (`start-dev`), `:97` (`KC_HOSTNAME_STRICT=false`);
  `application.yml:32-35, 52-68` (H2-Konsole an, `sa` ohne Passwort).
- **Problem:** Keycloak und Orchestrator teilen im Pod den Netz-Namensraum; für einen
  kompromittierten Keycloak-Container ist `localhost:8080/h2-console` „lokal“ – Vollzugriff auf die
  DB inklusive Signaturschlüssel (S-3) und KOBIL-PINs.
- **Vorschlag:** Konsole bleibt an (Projektentscheidung), aber DB-Passwort per Secret und
  `/h2-console/**` in die Admin-Chain; Keycloak mit `start --optimized` und festem Hostnamen.

### M-13 Account-Modul: Konsistenz bei Methodenersatz, Löschung und Verzeichnisänderungen

> **Behoben (2026-09-25), mit einer Korrektur am Befund:**
> - **Methodenersatz:** Ersetzt eine Singleton-Instanz die alte, widerruft `addAuthenticationMethod`
>   deren Nachweise, soweit die neue sie nicht selbst trägt (alte Telefonnummer). Der echte
>   Fehlerpfad war der Management-Pfad: Er schrieb keinen `PASSWORD_EXISTS`-Nachweis, sodass nach
>   dem Entfernen eines dort gesetzten Passworts „hat ein Passwort“ stehen blieb (Voraussetzung von
>   `enroll-kobil`). Jetzt schreibt er ihn wie der normale Weg.
> - **Löschung:** Das beschriebene Szenario (Gerätelink auf gelöschtes vorläufiges Konto) ist nicht
>   erreichbar – ein Link entsteht erst nach Identifizierung, Nachweis oder eingerichtetem Verfahren,
>   dann ist das Konto nicht mehr vorläufig. Umgesetzt ist nur der strukturelle Teil:
>   `AccountService.deleteProvisionalAccount` prüft die Regel selbst.
> - **Verzeichnisänderungen:** `PersonChangeListener` läuft einspurig; eine vom Verzeichnis
>   verschobene Versicherungsnummer wird beim veralteten Halter zurückgenommen, statt am Anker-Konflikt
>   endlos zu scheitern.
>
> Tests in `AccountServiceDbTest` und `MgmtPasswordIntegrationTest`.

- **Methodenersatz:** `account/AccountService.kt:517-521` deaktiviert eine Singleton-Vorgängerin,
  ohne deren Claims zu widerrufen (anders als `AccountDeletionService.revokeMethod`). Der
  Mgmt-Passwortpfad (`MgmtPasswordController.kt:77-83`) schreibt keinen `PASSWORD_EXISTS`-Claim –
  zwei Einrichtungswege, zwei Verhalten.
- **Löschung:** `JourneyService.kt:560-565` ruft das rohe `AccountService.deleteAccount` statt
  `AccountDeletionService`; die Regel „nur vorläufige Konten“ prüft nur der Aufrufer. Ein zuvor
  verknüpfter Gerätelink kann auf eine gelöschte Konto-ID zeigen.
- **Verzeichnisänderungen:** `account/internal/PersonChangeListener.kt:17-20` läuft parallel auf
  dem Default-Pool; wandert eine VERSNR zwischen Personen, scheitert das spätere Event am
  Anker-Konflikt und wird alle 5 min wiederholt – bei echter Kollision endlos, ohne Dead-Letter.
- **Vorschlag:** Vorgänger über `revokeMethod` deaktivieren; gemeinsamer Einrichtungsweg für
  Claims; benannte Operation `deleteProvisionalAccount`, die `isProvisional` selbst prüft;
  Listener einspurig, Konflikt per `RetractionAnchor.PERSON_DIRECTORY` auflösen.

---

## 3. Niedrig und konzeptionell

- ~~**DPoP ohne Default-Deny**~~ (erledigt 2026-09-25, `ApiBoundaryArchitectureTest`): Die Prüfung hängt am Argument-Resolver (`@BindingKey`,
  `DpopBindingKeyResolver.kt:45`), nicht an einem Filter. Ein neuer Handler ohne Parameter ist
  stillschweigend offen. Vorschlag: ArchUnit-Regel „jeder Handler unter `API_V1` hat `@BindingKey`
  oder steht in einer benannten Ausnahmeliste“.
- **Kein `DPoP-Nonce`:** Proofs sind 150 s im Voraus berechenbar (`DpopValidator.kt:66`). Bewusst,
  gehört aber in docs/09.
- **Proxy-Header undeklariert:** `server.forward-headers-strategy` ist nirgends gesetzt
  (`tool_api/RequestUrls.kt:9-24`); auf OpenShift funktioniert `htu` nur durch Spring Boots
  Kubernetes-Erkennung. In der Variante `openshift` ausdrücklich setzen, mit vertrauenswürdigen Proxies.
- **Drosselung als Opt-in je Handler:** `ToolControllerSupport.chargeThrottles` (`:256-300`) zählt
  nur mit `attemptedPersonId`/`attemptedAccountId` (siehe S-6). `ToolOutcome.Failed` für IDENT und
  LOOKUP_AUTH sollte das Subjekt typseitig verlangen.
- **Versand-Drosselung:** Aktivierung von `auth-sms`/`auth-email` prüft `isSendThrottled` nicht;
  Rufnummern-Regex (`EnrollSmsFlow.kt:98`) ohne Präfix-Allowlist.
- **Geteilte `DeviceEnrollment`-Zeile über Konten:** `EnrollDeviceToolHandler.kt:57-66`
  (`findByThumbprint ?: save`); Löschung bei einem Konto lässt das andere mit 500 zurück.
- **Mehrinstanz-Methoden:** `performAcceptProof` liest `enrolledUnderAcr` von der ersten aktiven
  Instanz, nicht von der genutzten (`JourneyActionExecutor.kt:464-467`).
- **Audit:** `established_acr` im Claim-Log ist der ungedeckelte Tool-Wert (Widerspruch zu ADR-5);
  Audit-Tabellen hängen per `ON DELETE CASCADE` am Konto (`V2__account.sql`); Methodenlebenszyklus
  ohne Append-only-Eintrag. Entscheiden, was eine Kontolöschung überleben muss.
- **Widerruf zum selben Zeitpunkt:** `AccountClaimRepository.kt:49` (`>=`) plus abweichende
  Normalisierung von Anker und Log für Restricted-IDs entkräften einen gerade gesetzten Claim.
- **Toter, scharfer Zweig:** `IdentityMatchingService.kt:54-58, 89-105` (tool-bescheinigte KVNR)
  ist unerreichbar, würde aber mit einem künftigen Tool still aktiv. Entfernen oder als Regel erzwingen.
- **Interessenten-Konto kann eine zweite Identität annehmen:** `JourneyActionExecutor.kt:172`
  ohne Abgleich mit den schon bescheinigten Stammdaten des Kontos.
- **Namensvetter-Risiko:** Korrelation vergleicht nur Name, Vorname, Geburtsdatum
  (`IdentityMatchingService.kt:47-48`). Adresse bewusst ein- oder ausschließen und in ADR-18 festhalten.
- **Zweitkonto-Zweig tot** – *behoben 2026-09-25 (Phase D 21): Zweig entfernt, Doku angeglichen.*
  `JourneyActionExecutor.kt:160-186` ist über FAST_ACCESS unerreichbar,
  weil `journey.accountId` beim Start aus dem Kanal vorbelegt wird; Doku 04 §2 beschreibt ein
  Verhalten, das nur mit `intent=register` existiert.
- **Frontend-Thumbprint** (`frontend/src/dpop.ts:89-98`) weicht vom RFC-7638-Wert des Backends ab –
  nur Anzeige, aber irreführend.
- **Passwort:** PBKDF2 mit 210 000 Iterationen (OWASP 2023: 600 000), keine Maximallänge, keine
  Prüfung gegen bekannte Passwortlisten.
- **KOBIL-Aktivierungsgeheimnisse** 24 h im Klartext in der ToolSession, bei jedem GET erneut
  ausgeliefert (`EnrollKobilToolHandler.kt:139-152`).
- **Web-Kanal:** Keycloak-Refresh-Token in `sessionStorage` (`WebChannelView.tsx:21-36`); kein
  `state`/`iss`-Check im OIDC-Callback (`webOidc.ts:151-177`). Klären, ob die Regel „Refresh-Token
  nie ins Frontend“ auch hier gelten soll.
- **Unauthentifizierter Realm-Schreibzugriff:** `DemoLoginThemeController.kt:19-36` schaltet ohne
  Login realm-weit das Login-Theme um, über den Master-Admin-Zugang (S-3).
- **Personenverzeichnis-Verwaltung ohne Login:** `PersonenverzeichnisController.kt:40-84` stellt
  Freischaltcodes im Klartext aus und ändert Stammdaten. Als Fremdsystem-Mock deklariert; in einer
  erreichbaren Instanz trotzdem ein Kontoübernahme-Pfad – hinter Admin-Auth legen oder per Profil abschalten.
- **Fehlerantworten:** `OrchestratorExceptionHandler.kt:56-61` reicht jede
  `IllegalArgumentException.message` durch (Klassennamen aus Bibliotheken). Kein CSP-Header.
- **Keycloak-Kleinkram:** kein `jti`-Replay-Schutz für die Konto-Assertion
  (`AccountTokenGrantType.java:162-188`); unbekannte `kid` erzwingt ohne Backoff einen JWKS-Abruf
  (`KeycloakJwkSource.kt:31`); Redirect-URIs mit Wildcard; keine Schlüsselrotation.
- **Compose lokal:** Ports ohne `127.0.0.1:`-Präfix, Standardpasswörter – im geteilten Netz aus
  dem LAN erreichbar.

---

## 4. Geprüft und als korrekt befunden

- **DPoP-Proof:** `typ=dpop+jwt`, nur ES256/384/512, JWK nur öffentlich, `htm`/`htu`,
  `iat`-Fenster, `jti`-Replay per DB-Primärschlüssel in eigener Transaktion, Thumbprint nach RFC 7638.
  Geräte-Proof mit eigenem `typ`, beide akzeptieren einander nicht.
- **Kanalbindung:** Alle kanal- und toolsession-adressierten Endpunkte gehen durch
  `ChannelAccessGuard` (Vergleich in konstanter Zeit); kein kanalübergreifender Zugriff über
  `toolSessionId` gefunden. APP- und Keycloak-Kanäle sind gegeneinander abgeschottet.
- **DeviceAccountLink:** ein Schreibpfad, Umbinden nur nach Rückfrage, widerruft schlüsselgebundene
  Credentials des Vorkontos.
- **Peer-Auth:** Signatur via JWKS, `iss`/`aud`/`htm`/`htu`/`iat`, `jti` einmalig; Mgmt-Endpunkte
  binden `channel_anchor` an die Konto-ID im Pfad.
- **Lookup-Tools:** gleiche Antwortform und gleiche Kosten für unbekannt/gesperrt/gedrosselt.
- **Codes und Passwörter:** `SecureRandom`, HMAC mit Pepper, Vergleich in konstanter Zeit,
  PBKDF2 mit Salz und Dummy-Hash.
- **Anker:** Eindeutigkeit per DB (`ux_anchor_value`), Konflikt → 409 mit vollständigem Rollback;
  Auflösung nur über Anker, nie über Attributkombinationen.
- **Refresh-Token im App-Kanal** verlässt das Backend nie.
- **Modulgrenzen:** `ApplicationModules.verify()` läuft, keine Zugriffe auf `internal`-Pakete.
- **Frontend:** kein `dangerouslySetInnerHTML`, kein Open Redirect, DPoP-Schlüssel nicht exportierbar.
- **Keycloak-Realm:** Browser-Clients public mit PKCE S256, kein Implicit/Direct Grant;
  FreeMarker mit Auto-Escaping.
- **Container:** Nicht-Root-User, keine Secrets im Image; kein Actuator im Classpath.

---

## 5. Reihenfolge

Die Abarbeitung folgt dem Fahrplan in
[review-2026-09-bewertung-und-massnahmen.md](review-2026-09-bewertung-und-massnahmen.md),
Abschnitt 4. Er verzahnt die Einzelbefunde dieses Dokuments mit den strukturellen Maßnahmen, damit
kein Befund zweimal gelöst wird (etwa S-6: erst schnell, dann in Phase D durch den Typ endgültig).
Dieses Dokument bleibt das Nachschlagewerk mit den Fundstellen.
