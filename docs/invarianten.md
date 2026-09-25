# Invarianten und womit sie gesichert sind

Dieses Register sammelt die Regeln, auf die sich der Backend-Kern verlässt, und nennt je Regel den
**Mechanismus**, der sie erzwingt ([Fahrplan](review-2026-09-bewertung-und-massnahmen.md), P-1,
Schicht 4). Eine Regel ohne Mechanismus gilt nur per Konvention – sie steht hier trotzdem, als
sichtbare **Lücke**, damit sie nicht im Kommentar verschwindet.

`InvariantRegisterTest` liest diese Datei und prüft, dass jeder genannte Mechanismus existiert. Die
Mechanismen stehen deshalb in einer festen Form, je in Backticks:

- **`test:<Klasse>`** – ein Test (Unit, Integration, modellbasiert) unter `src/test`.
- **`archunit:<Klasse>`** – eine Architekturregel unter `src/test`.
- **`type:<Klasse>`** – ein Typ unter `src/main`, der die falsche Form unausdrückbar macht.
- **`sql:<Name>`** – ein benannter Constraint oder Index in einer Flyway-Migration.

Eine Regel ohne einen dieser Einträge muss als `Lücke` markiert sein, mit dem Fahrplan-Schritt, der
sie schließen soll.

---

## Kanal und Journey

- **I-1 Ein beendeter Kanal (`LOGGED_OUT`, `EXPIRED`) wird nie wieder `AUTHENTICATED`.**
  - Mechanismus: `test:CancelLogoutIntegrationTest`, `test:ModelBasedJourneyTest`
  - Lücke: kein Typ, kein Constraint – Fahrplan Phase C (Constraint) und D (`LiveChannel`).
- **I-2 Eine verbrauchte, abgebrochene oder fehlgeschlagene Journey nimmt keine Tool-Ergebnisse mehr an.**
  - Mechanismus: `test:CancelLogoutIntegrationTest`, `test:DeviceBindingIntegrationTest`, `test:ModelBasedJourneyTest`
  - Lücke: gesichert durch `isCurrent` und das Ablaufen der ToolSession, nicht durch einen Typ – Phase D (`RunningJourney`).
- **I-3 Ein Kanal hat höchstens eine laufende (`STARTED`) Journey.**
  - Mechanismus: `test:ModelBasedJourneyTest` (fand die Verletzung, Review M-5), `type:JourneyService` (`start` bricht die laufende Kette ab)
  - Lücke: kein DB-Constraint – H2 kennt keine partiellen Indizes; mit einer anderen Datenbank Phase C.
- **I-4 Ein `AUTHENTICATED`-Kanal hat Evidenz mit mindestens einem Faktor.**
  - Mechanismus: `test:ModelBasedJourneyTest`
  - Lücke: kein Constraint – Phase C.
- **I-5 Ein Kanal wechselt nie still das Konto; ein anderes Konto ist ein Fehler, kein Umbinden.**
  - Mechanismus: `test:KcChannelIntegrationTest`

- **I-22 Eine abgelaufene Anmeldung wird nie still verlängert; jede Abmeldung verwirft die Tokens.**
  - Mechanismus: `test:TokenServiceTest`, `test:KcTokenProviderTest`, `test:CancelLogoutIntegrationTest`, `type:SessionExpiredException`

## DPoP und Zugang

- **I-6 Jeder HTTP-Handler ist per DPoP an einen Kanal gebunden (`@BindingKey`) oder mit seinem eigenen Schutz benannt.**
  - Mechanismus: `archunit:ApiBoundaryArchitectureTest`, `type:BindingKey`
- **I-7 Ein DPoP-Proof gilt nur einmal.**
  - Mechanismus: `test:DpopValidatorTest`, `type:DpopReplayProtectionService`
- **I-8 Ein Kanal ist an genau einen Schlüssel gebunden, APP- und Keycloak-Kanal schließen sich aus.**
  - Mechanismus: `sql:ck_channel_session_binding_key`, `type:ChannelAccessGuard`

## Konto und Identität

- **I-9 Ein Ankerwert gehört höchstens einem Konto; je Konto höchstens ein Anker je Art.**
  - Mechanismus: `sql:ux_anchor_value`, `sql:ux_anchor_account_type`, `test:AccountServiceDbTest`
- **I-10 Nur `JourneyActionExecutor` befragt `IdentityResolver` – aufgelöst wird nur dort, wo auch gebunden wird.**
  - Mechanismus: `archunit:OrchestratorArchitectureTest`
- **I-11 Der Inhaber kann nur die E-Mail-Adresse selbst zurücknehmen, keinen Identitätsanker.**
  - Mechanismus: `type:AnchorRule`, `test:AttributeRulesTest`, `test:AccountServiceDbTest`, `test:ManageMethodsIntegrationTest`
- **I-12 Ein Korrelationsschritt (`ident-kvnr`) verrät nicht, ob eine fremde Nummer existiert.**
  - Mechanismus: `test:IdentKvnrToolHandlerTest`, `test:IdentEidAssignmentIntegrationTest`
  - Lücke: das Subjekt im `Failed` ist nicht per Typ erzwungen – Phase D.
- **I-13 Je Konto höchstens eine aktive Instanz einer Singleton-Methode (z. B. Passwort).**
  - Mechanismus: `test:ModelBasedJourneyTest` (für das Passwort)
  - Lücke: kein Constraint, nur `AccountService.addAuthenticationMethod` – Phase C.
- **I-14 Kein Gerätelink zeigt auf ein gelöschtes Konto.**
  - Mechanismus: `test:ModelBasedJourneyTest`; ein Link entsteht nie für ein vorläufiges Konto, und nur das darf ohne `AccountDeletionService` verschwinden (`AccountService.deleteProvisionalAccount` prüft es selbst)
  - Lücke: kein Fremdschlüssel (Schemas je Modul, ADR-16), Review M-13 – Phase C.

- **I-21 Was eine ersetzte Instanz nachwies und die neue nicht, gilt nicht mehr; jede Passwort-Instanz trägt ihren eigenen Nachweis.**
  - Mechanismus: `test:AccountServiceDbTest`, `test:MgmtPasswordIntegrationTest`

## Keycloak

- **I-15 Der Account-Sync übernimmt nie den Keycloak-User eines anderen, noch bestehenden Kontos.**
  - Mechanismus: `test:KeycloakMirrorResolutionTest`, `test:KeycloakAccountSyncServiceTest`
- **I-16 Jeder Keycloak-Client des Orchestrators signiert mit seinem eigenen Schlüssel.**
  - Mechanismus: `test:OrchestratorClientAssertionSignerTest`
- **I-17 Das Vertrauen in ein selbstsigniertes Keycloak-Zertifikat gilt nie JVM-weit.**
  - Mechanismus: `test:KeycloakHttpTest`, `type:KeycloakHttp`

## Verfahren und Fremdsysteme

- **I-18 Ein QR-Login meldet einen Browser erst mit dem Bestätigungscode aus der App an, und nur einmal.**
  - Mechanismus: `test:AuthQrFlowIntegrationTest`, `type:QrLoginBrowserSide`
- **I-19 Kein Code und kein Empfänger landet auf der Konsole.**
  - Mechanismus: `archunit:OrchestratorArchitectureTest`
- **I-20 Der Kern erreicht simulierte Fremdsysteme nur über benannte Kanten oder Ports.**
  - Mechanismus: `archunit:SimulationBoundaryArchitectureTest`, `type:PersonMasterData`
