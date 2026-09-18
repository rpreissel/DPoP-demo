# Implementierungsplan: Account-Attribute und Trust-Begriffe vereinheitlichen

> **Stand 2026-09-17 ([ADR-14](../12-entscheidungen.md))**: Alle Pakete sind umgesetzt und durch die
> Schema-Konsolidierung überholt: Die hier noch „als typisierte Leseprojektion erhaltenen“ Spalten
> `Account.personId`/`email`/`emailConfirmedAt` gibt es nicht mehr — `AccountProfile` liest dieselben
> Werte aus `account_anchor`. `OwnedColumn`/`ExternalLiveLookup` sind entfallen (lokal konsolidiert
> heißt jetzt: ist Anker). Spalten heißen `claim_source`, `attribute_type`, `normalized_value`.

Status: **Implementierungsplan mit praezisiertem Zielbild**. Die atomare Account-Anlage und
KVNR-Aufloesung wurden nachtraeglich freigegeben; aktueller Vertrag:
[ADR-11](../12-entscheidungen.md), Nachtrag 3, und [Konsistenz-Regeln](../07-betrieb.md).

Dieses Dokument konkretisiert das [Claims-Zielbild](claims-modell-und-vertrauensanker.md).
Die zusaetzlichen Terminologievorschlaege bleiben gesondert zur Auswahl gestellt.

## Arbeitsanweisung fuer das implementierende Modell

Arbeite die Pakete unten in der angegebenen Reihenfolge ab. Lies zuerst `docs/00-agent-quickstart.md`, danach dieses Dokument und nur die im aktuellen Paket genannten Dateien. Kotlin-Pfade in den Arbeitspaketen sind relativ zu `src/main/kotlin/com/example/dpop/`.

Beginne erst nach expliziter Implementierungsfreigabe. Suche dann passende Beads-Aufgaben beziehungsweise lege sie an; verwende Beads fuer den dauerhaften Arbeitsstatus, nicht dieses Dokument als fortlaufendes Arbeitsprotokoll.

Pro Paket: Ist-Code lesen, gezielt aendern, zugehoerige Tests ausfuehren und die genannten Fertig-Kriterien pruefen. Keine parallelen Alt-/Neu-Implementierungen, kein neues Framework und keine zusaetzlichen Features. Ein Umbenennen ist erst fertig, wenn alle produktiven Aufrufer und Test-Fixtures mit umgestellt sind.

Wenn eine gesperrte Datei benoetigt wird, an diesem Paket stoppen und den fehlenden Zugriff melden. Keine andere Zugriffsmethode benutzen und kein Schema aus Kommentaren nachbauen.

## Ziel und bestaetigte Entscheidungen

PersonId und E-Mail erhalten denselben technischen Claim-, Anker- und Projektionspfad.
Die fachlichen Unterschiede werden explizite typisierte Regeln, keine parallelen Implementierungen.

Mit dem Nutzer abgestimmt:

- `Account.personId`, `email` und `emailConfirmedAt` sowie `AccountProfile` bleiben als typisierte Leseprojektion erhalten.
- PersonId bleibt nach Erstbindung unveraenderlich und hat bei der Aufloesung Vorrang vor anderen Ankern. E-Mail bleibt nach erneutem Nachweis wechselbar.
- Kontouebergreifende Ankerkonflikte werden abgewiesen; keine automatische Uebernahme oder Zusammenfuehrung.
- Durchgaengige Umstellung bis `ToolOutcome` und Journey: `Completed.Identified.personId` entfaellt als separates Datenfeld zugunsten des vorhandenen Claims.
- Die heutigen IDENT-Verfahren verlangen weiterhin genau einen gueltigen PersonId-Claim. Claims-only-Identifizierungen werden nicht freigeschaltet.
- Trust-Begriffe werden vereinfacht, ohne Quellenrang, Identifikatorstaerke und Authentifizierungsniveau fachlich zusammenzulegen.
- `TrustAnchor` wird `ClaimSource`, `AnchorClass` wird `TrustLevel`; der Rang wird zentral aus der Quelle abgeleitet.
- `AnchorType` entfaellt als doppelte Attribut-Taxonomie. `AttributeType` ist der einzige Attribut-Schluessel, auch fuer Ankeroperationen.
- Implementierung der atomaren Account-Anlage und Live-KVNR-Aufloesung ist freigegeben; Ausgabe als Mail-Patch, kein Commit, Push oder Remote-Sync.

## Analyse des Ist-Zustands

| Bereich | PersonId | E-Mail |
| --- | --- | --- |
| Claim | `PERSON_ID` vorhanden, `recordClaim` schreibt nur Provenanz | `EMAIL` schreibt Provenanz, Projektion und Anker |
| Konsolidierung | Als `ExternalLiveLookup` klassifiziert, obwohl die Referenz selbst lokal gespeichert wird | `OwnedColumn` |
| Account-Schreibung | `findOrCreateAccount`/`bindPersonId` schreiben direkt | `recordClaim` -> `consolidateOwnedColumn`; Seed nutzt `confirmEmail` ohne Claim-Log |
| Aufloesung | `AccountRepository.findByPersonId` und eigener Resolver-Zweig | `AccountDirectory.resolveByAnchor`/`account_anchor` |
| Tool-Ergebnis | Separates Pflichtfeld plus derselbe Wert in `claims` | Nur Claim in `Completed.Enrolled` |
| Regeln | Journey prueft Erstbindung, falsche Person und fremdes Konto | AccountService prueft Ankerbesitz und ersetzt alten E-Mail-Anker |

Wesentliche gelesene Dateien:

- `src/main/kotlin/com/example/dpop/account/AccountService.kt`, `AccountProfile.kt`, `internal/Account.kt`, `internal/AccountAnchor.kt`, `internal/AccountAttribute.kt`, `internal/IdentityMatchingService.kt`
- `src/main/kotlin/com/example/dpop/tool_api/{AnchorType,ConsolidationStrategy,AccountDirectory,IdentityResolver}.kt`
- `src/main/kotlin/com/example/dpop/tool_spi/{Claims,ToolOutcome}.kt`
- `src/main/kotlin/com/example/dpop/orchestrator/journey/JourneyService.kt`
- `src/main/kotlin/com/example/dpop/orchestrator/api/v1/OrchestratorExceptionHandler.kt`
- `src/main/kotlin/com/example/dpop/demo_seed/internal/KcDemoAccountSeeder.kt`
- Account-/Resolver-Unit-Tests und `RegisterEnrollFirstFlowIntegrationTest`.

Weitere Befunde:

- `AnchorType.Kvnr` existiert bereits; KVNR-Claims materialisieren heute wegen `ExternalLiveLookup` aber keinen Anker. Das wird nicht nebenbei geaendert.
- `MatchedVia.PersonId` transportiert derzeit die Bindungsstaerke 3, andere Anker 2, Attributmatching 1.
- `assertClaimsCovered` prueft Deklaration und Herkunft, aber nicht Eindeutigkeit pro Attribut oder den erforderlichen PersonId-Claim.
- `performAdoptIdentity` und `performConfirmIdentity` schreiben Personenbindung vor dem Claim-Log; nur der Adopt-Resolver-Aufruf uebersetzt aktuell `IdentityConflictException` explizit.
- Keycloak-Sync verwendet `AccountProfile.personId` fuer Live-Stammdaten und `email`/`emailConfirmed`. Diese Lesepfade koennen unveraendert bleiben.
- `docs/ideen/claims-modell-und-vertrauensanker.md` beschreibt bereits das passende Zielbild, bezeichnet aber inzwischen vorhandene Teile noch als nicht umgesetzt. ADR-10 bis ADR-12 bleiben die fachliche Grundlage.
- Ein Typ `TrustType` wurde im untersuchten Kotlin-Code nicht gefunden. Nicht nach einem hypothetischen Typ refaktorieren.
- `DefaultAuthPolicy.requiresSatisfied` ist heute eine ausdrueckliche E-Mail-Spezialisierung: bestaetigte E-Mail und geforderter Rang hoechstens `PROVEN`. Die KDocs beschreiben teilweise schon eine generische, retraktionsfaehige Zukunft, die der Code noch nicht implementiert.

## Zielentwurf

### Kleines und eindeutiges Vokabular

| Frage | Zieltyp/Begriff | Beispiel | Nicht verwechseln mit |
| --- | --- | --- | --- |
| Was wird behauptet? | `AttributeType` | `PERSON_ID`, `EMAIL` | Quelle |
| Wer behauptet es? | `ClaimSource` | `ext_stammdaten`, `ident-eid` | Suchschluessel |
| Welchen Rang hat diese Quelle? | `TrustLevel` | `STAMMDATEN`, `PROVEN`, `SELF_REPORTED` | ACR/LOA |
| Wie stark war der konkrete Nachweis? | vorhandener `AcrLevel` | `loa2` | Quellenrang |
| Wie eindeutig bindet ein Treffer? | `bindingStrength` der Attributregel | PersonId 3, andere Anker 2 | Quellenrang |
| Woher kommt der effektive Wert? | vorhandene `ConsolidationStrategy` | `OwnedColumn`, `ExternalLiveLookup` | Vertrauen |

Zwei Quellenbegriffe bleiben absichtlich bestehen: Die konkrete Herkunft muss fuer Audit und Deklarationspruefung erhalten bleiben; mehrere konkrete Tool-Quellen teilen sich denselben Rang. Ein einzelnes Enum statt Quelle UND Rang wuerde Information verlieren oder pro Tool neue Rangstufen erfinden.

Verbindliche Umbenennungen:

| Alt | Neu |
| --- | --- |
| `TrustAnchor` | `ClaimSource` |
| `Claim.trustAnchor`, `ClaimDeclaration.trustAnchor` | `source` |
| `AnchorClass` | `TrustLevel` |
| `ClaimRequirement.minAnchorClass` | `minTrustLevel` |
| `anchorClassOf(source)` | `source.trustLevel` |
| `AnchorType.Email` / `.Kvnr` | `AttributeType.EMAIL` / `.KVNR` |
| `AnchorType.of(type)` | Attributregel: `type.anchorBindingStrength != null` |

`ClaimSource` bleibt ein nominaler Value-Typ. `EXT_STAMMDATEN`, `SELF_REPORTED` und `of(ToolId)` behalten ihre Werte und Bedeutung. `trustLevel` wird berechnet, nicht separat im Claim gespeichert oder vom Aufrufer gesetzt. Die bestehenden drei Rangwerte bleiben unveraendert. `ConsolidationStrategy`, `AcrLevel`, `FactorType` und `EvidenceAxis` werden nicht mit Trust verschmolzen.

Persistenzkompatibilitaet: `account_attribute.trust_anchor` und die bisherigen gespeicherten Quellenstrings bleiben erhalten. Falls die Entity-Property in `source` umbenannt wird, explizit auf `trust_anchor` mappen. `account_anchor.anchor_type` speichert weiterhin dieselben Attribut-Wire-Namen. Keine DB-Spaltenmigration allein wegen besserer Kotlin-Namen. An Serialisierungsgrenzen vorhandene JSON-Vertraege gezielt pruefen und erhalten; keine globale Such-und-Ersetze-Aktion auf gespeicherten Daten.

### Ein technischer Pfad, explizite Semantik pro Typ

Keine neue Typ-Hierarchie als Ersatz fuer `AnchorType`. Seine kleinen Regeln liegen als Extensions auf `AttributeType` in `tool_api/AttributeRules.kt`; `tool_spi` bleibt frei von Persistenzwissen:

- `anchorBindingStrength: Int?`: `PERSON_ID` = 3, `EMAIL` = 2, sonst `null`, auch fuer KVNR. `null` bezeichnet explizit ein Nicht-Anker-Attribut, keinen fehlgeschlagenen Lookup.
- `normalizeAnchorValue(value: String): String`: PersonId als kanonische, validierte Long-Darstellung; E-Mail trim/lowercase. Nicht-Anker-Aufrufe sind Vertragsfehler, kein Durchreichen des Rohwerts. Ungueltige PersonId darf nicht still auf schwaecheres Matching zurueckfallen.
- `allowsAnchorReplacement: Boolean`: PersonId = false, E-Mail = true. KVNR wird ausschliesslich von `ext_stammdaten` verwaltet und kann dort geaendert werden; keine lokale Ankerersetzung.
- KVNR-Suchen normalisieren trim/uppercase und fuehren ueber `PersonDirectory.findPersonIdByKvnr` zum PersonId-Anker. Historische KVNR-Claims bleiben Provenanz, keine aktuelle Account-Zuordnung. Es gibt weder einen lokalen KVNR-Anker noch eine synchron zu haltende Kopie.
- `PERSON_ID` wird wie `EMAIL` lokal projiziert (`OwnedColumn`). Namen, Geburtsdatum und andere bisher delegierte Stammdaten bleiben `ExternalLiveLookup`.
- `Account.applyOwnedColumn` setzt beide Projektionsarten. Die generische Service-Operation erzwingt die typabhaengigen Bindungsregeln vor der Mutation.
- `AccountService.recordClaims(accountId, claims)` ist die gemeinsame transaktionale Mehrclaim-Operation: gesamte Eingabe pruefen und Log, Anker und Account gemeinsam schreiben. `recordClaim` nur als delegierenden Komfort-Wrapper behalten, falls benoetigt. Kein zusaetzlicher Service-Layer.
- `AccountChanged` muss erst den vollstaendigen Zustand der erfolgreichen Uebernahme repraesentieren; bestehende After-Commit-Synchronisation bleibt erhalten.

Die Fallunterscheidungen liegen zentral in dieser kleinen Datei, nicht erneut in jedem Aufrufer. Kein frei konfigurierbares Policy-Objekt, keine Registry und keine weitere `TrustType`-/`IdentifierType`-Taxonomie einfuehren.

### Gemeinsame Aufloesung

Lokale Account-Identifikator-Lookups laufen ueber `account_anchor`, auch PersonId. KVNR wird vorgeschaltet live in den Stammdaten zur PersonId aufgeloest. `AccountDirectory.resolveByAnchor(type: AttributeType, value)` und `anchorValue(accountId, type: AttributeType)` behalten ihre Operationsnamen, pruefen aber die lokale Anker-Eignung des Typs explizit. Typisierte Komfortmethoden bleiben als Extensions: PersonId und E-Mail delegieren an denselben Ankerpfad, KVNR zuerst an `PersonDirectory`.

Der Resolver priorisiert anhand expliziter Ankerstaerke statt eines separaten PersonId-Repository-Zweigs oder der zufaelligen Claim-Reihenfolge. `MatchedVia.Anchor` traegt auch PersonId; dessen Staerke wird aus der Typregel abgeleitet, `MatchedVia.PersonId` entfaellt nach Umstellung seiner Verbraucher.

Mehrere eindeutige Anker auf verschiedene Konten sind ein Konflikt, kein Grund, einfach den ersten Treffer zu nehmen. Attributmatching bleibt der bestehende nachrangige, potenziell mehrdeutige Fallback. Die Konsistenzpruefung gegen `PersonDirectory` bleibt bestehen.

`bindingStrength` NICHT aus `TrustLevel.rank` berechnen: Ein PersonId-Treffer bleibt staerker als ein E-Mail-Treffer, unabhaengig davon, welches Tool die Claims geliefert hat. Die in `docs/13-review-domaenen-db-modell.md` angesprochene Reihenfolgeabhaengigkeit wird hier durch die explizite Identifikatorstaerke beseitigt, nicht durch Vermischung dieser beiden Dimensionen.

### Erfolgsergebnis und Orchestrierung

`Completed.Identified` traegt PersonId nur noch als Claim. Gemeinsame Vertragsvalidierung verhindert fehlende, doppelte, ungueltige und nicht deklarierte Claims, bevor aufgeloest oder geschrieben wird.

Die Journey entscheidet weiterhin ueber Adoptieren versus Bestaetigen eines bekannten Kontos. Die Account-Schicht verantwortet Bindung und Konflikte. Neue Konten entstehen ohne vorab direkt geschriebene PersonId und erhalten ihre Bindung durch dieselbe atomare Claim-Uebernahme. Auch ein Treffer ueber andere Attribute auf ein bislang ungebundenes Konto muss dadurch seine PersonId korrekt erhalten.

## Weitere Begriffe: Vorschlaege, noch kein Implementierungsauftrag

Die folgenden Befunde stammen aus den gelesenen Domain-Typen und ihren unmittelbaren Verbrauchern. Dies ist keine vollstaendige Terminologiepruefung des gesamten Projekts.

**Wichtig fuer die Umsetzung:** Keine dieser zusaetzlichen Umbenennungen automatisch mit ausfuehren. Die sieben Arbeitspakete unten verwenden bewusst weiter die heutigen Namen, sofern sie nicht in der verbindlichen Trust-Umbenennungstabelle stehen. Erst nach Auswahl durch den Nutzer den jeweiligen Vorschlag in betroffene Pakete und Tests einarbeiten.

### Hoher Nutzen, nah am aktuellen Vorhaben

| Heute | Vorschlag | Konkretes Missverstaendnis | Umfang bei spaeterer Freigabe |
| --- | --- | --- | --- |
| `Resolution.NewInteressent` | `Resolution.NoMatch` | Der Resolver hat keinen passenden Account gefunden. Daraus folgt weder, dass die Person neu ist, noch dass sie keinen Stammdatensatz hat. Die Journey kann heute gerade fuer eine vorhandene Person ein Konto anlegen. | `tool_api/IdentityResolver.kt`, Resolver, Journey und Resolver-Tests. Ergebnis bleibt identisch; kein neuer Interessenten-Flow. |
| `AccountAttribute` | `AccountClaim` | Die Entity speichert eine historische Behauptung mit Herkunft und Zeitpunkt, nicht den aktuell gueltigen Attributwert. Der aktuelle Wert steht in der Projektion. | Entity und Repository im Account-Modul sowie Service/Tests. Tabelle `account_attribute` unveraendert lassen; vom SPI-Wertobjekt `Claim` durch den Account-Bezug unterscheiden. |
| `createUnidentifiedAccount()` | `createUnboundAccount()` | Fehlende PersonId bedeutet genau: keine Bindung an eine Stammdaten-Person. Es kann bereits eine bestaetigte E-Mail oder andere Evidenz geben. Im spaeteren Claims-only-Zielbild waere sogar eine Identifizierung ohne Personenbindung moeglich. | AccountService, Journey, Seed und Tests. KDoc muss ausdruecklich sagen: ungebunden an eine Stammdaten-Person, nicht ungebunden an Kanal/Geraet. Kein neues Account-Zustandsfeld. |

Empfehlung: `NoMatch` und `AccountClaim` zuerst entscheiden; sie beseitigen konkrete semantische Verwechslungen. `createUnboundAccount` ist ebenfalls praeziser, braucht aber wegen der verschiedenen Bindungen im Projekt die genannte KDoc. Alternativ ist `createAccountWithoutPersonBinding` laenger, dafuer voellig eindeutig.

### Sinnvoll, aber als separater Terminologie-Schritt

| Heute | Vorschlag | Begruendung und Grenze |
| --- | --- | --- |
| `orchestrator.policy.AuthEvidence.factors: List<MethodEvidence>` | `methodEvidence` | Ein Eintrag ist ein Methodennachweis und kann mehrere `FactorType` enthalten. Methode und Faktor sind fuer MFA ausdruecklich nicht dasselbe. Policy, Factory, RestoreData und Tests gemeinsam umstellen; JSON-Grenzen vorab pruefen und Drahtformat erhalten. |
| `AuthEvidence` in `orchestrator.policy` und `orchestrator.session` | Policy-Wertobjekt `EvidenceSnapshot`, persistente Entity weiter `AuthEvidence` | Die identischen Namen sind laut KDoc absichtlich gewaehlt, erzwingen aber Import-Aliase wie `CoreAuthEvidence`. Der neue Name benennt die abgeleitete Policy-Sicht, ohne eine zweite fachliche Wahrheit zu behaupten. Breiter Aufruferradius: nicht als beilaufiges Account-Refactoring. |
| `AuthenticationMethod` / `AuthMethodView` fuer gespeicherte Eintraege mit Instanz-ID | `AccountMethodRegistration` / `AccountMethodRegistrationView` | Diese Objekte beschreiben eine konkrete eingerichtete Methodeninstanz, nicht den Methodentyp. Wichtig bei mehreren Geraeten. Nicht `Credential` nennen: das eigentliche Credential gehoert weiterhin dem Methodenmodul und wird nur referenziert. API-Felder, IDs und EnrollmentRef-Semantik erhalten. |
| `ChannelSession.channelAnchor` | `peerFlowBinding` | Der Wert bindet einen Keycloak-Peer-Nachweis an genau einen Flow; er ist weder ein Account-Suchanker noch die langlebige Keycloak-Session. Nur als separater Peer-Auth-Begriffsschritt pruefen. Kein Umbenennen von JWT-Claims/DB-Spalten nebenbei und keine Aenderung der Bindungspruefung. |

### Absichtlich beibehalten oder nur dokumentarisch praezisieren

- `personId` bleibt: Das ist tatsaechlich der Schluessel der externen Stammdaten-Person, nicht `accountId`. Nach der technischen Vereinheitlichung muss es nicht kuenstlich `identityId` heissen.
- `AccountAnchor` bleibt im vereinbarten Umfang als Name fuer einen eindeutigen Account-Suchschluessel. Durch `ClaimSource` entfaellt bereits die groesste Mehrdeutigkeit. Nicht jede Verwendung des Wortes Anchor gleichzeitig umbenennen.
- `Claim`, `ClaimDeclaration` und `ClaimRequirement` bleiben verschieden: konkretes Ergebnis, zugesicherte Tool-Faehigkeit und Voraussetzung sind unterschiedliche Vertragsrichtungen.
- `AcrLevel`, `EvidenceAxis`, `FactorType`, `acrFloor` und `targetAcr` bleiben getrennt. Die Unterschiede tragen Sicherheitsregeln und sind keine blosse Typenduplikation.
- `ConsolidationStrategy.ExternalLiveLookup` vorerst nur praeziser dokumentieren: `recordClaims` fuehrt keinen Live-Lookup aus, sondern verzichtet auf lokale Projektion; der jeweilige Leser delegiert spaeter. Eine Umbenennung in `Delegated` waere fuer `PHONE_NUMBER` voreilig, weil dessen heutige Einordnung ausdruecklich nur provisorisch ist.
- `ClaimSource` und die vorhandene Evidenz-Herkunft `AmrSource` nicht zusammenlegen: Aussageherkunft und Herkunft eines Sitzungsnachweises haben unterschiedliche Werte und Regeln.

## Arbeitspakete und Abhaengigkeiten

### 1. `trust-vocabulary`: Quellen- und Rangbegriffe klaeren

Dateien: `tool_spi/Claims.kt`, `tool_spi/ToolDescriptor.kt`, alle Claim-erzeugenden Descriptors/Handler in `auth_email`, `id_fsc`, `id_eid`, `auth_password/Descriptors.kt`, `account/internal/IdentityMatchingService.kt`, `orchestrator/policy/DefaultAuthPolicy.kt`, `orchestrator/api/v1/tool/ToolControllerSupport.kt`, betroffene Tests.

Die Umbenennungstabelle vollstaendig umsetzen. `ClaimSource.trustLevel` ersetzt die freie Mapping-Funktion. Entity-Mapping zur vorhandenen DB-Spalte erhalten.

**Fertig wenn:** `ClaimsTest`, betroffene Handler-Tests und `DefaultAuthPolicyTest` gleiches Verhalten nachweisen; keine produktiven Referenzen auf `TrustAnchor`, `AnchorClass`, `anchorClassOf`, `minAnchorClass` verbleiben. Sowohl Angebotsfilter als auch direkte Tool-Aktivierung benutzen weiterhin dieselbe Voraussetzung.

**Nicht tun:** Die heutige E-Mail-spezifische `requiresSatisfied`-Implementierung in eine generische Trust-Engine umbauen, unbestaetigte E-Mail freischalten oder Quellenrang mit LOA gleichsetzen. Unfertige Zielbild-KDocs passend als Ausblick markieren.

### 2. `attribute-contract`: Doppelte Ankertaxonomie entfernen

Abhaengig von 1.

Dateien: `tool_api/AnchorType.kt` (entfaellt), `tool_api/AttributeRules.kt` (neu), `tool_api/ConsolidationStrategy.kt`, `tool_api/AccountDirectory.kt`, `tool_api/IdentityResolver.kt`, `tool_spi/Claims.kt`, vorhandene Anker-Aufrufer in `account` und `auth_email`.

Extensions wie oben beschrieben einfuehren; alle `AnchorType`-Argumente auf `AttributeType` umstellen. `AnchorTypeTest` durch Tests der Attributregeln ersetzen. Den Wechsel von PersonId zu `OwnedColumn` erst in Paket 4 gemeinsam mit `Account.applyOwnedColumn` und den Service-Tests aktivieren, sodass dieses Paket fuer sich lauffaehig bleibt.

Claim-Pruefung um Eindeutigkeit pro Attribut und gueltige Werte erweitern. Den Pflicht-PersonId-Check nur am IDENT-Erfolgsvertrag anwenden, nicht pauschal auf Enrollment oder den auch fuer andere Claim-Sets verwendeten Resolver. Bestehende Deklarations- und Quellenpruefung erhalten.

**Fertig wenn:** Kein produktiver `AnchorType` mehr existiert; E-Mail und externe KVNR-Suchen normalisieren wie bisher; PersonId normalisiert eindeutig; lokale Nicht-Anker-Aufrufe und ungueltige PersonId scheitern explizit.

### 3. `anchor-migration`: Bestand sicher auf PersonId-Anker umstellen

Abhaengig von 2. Vor Paket 4 muss der erlaubte Zugriff und der Migrationsweg geklaert sein. Pakete 3 und 4 zusammen ausliefern, nie den neuen Lookup vor dem Backfill aktivieren.

Neue additive Flyway-Migration nach Pruefung des tatsaechlichen Schemas; keine vorhandene Migration umschreiben. Bestehende nichtleere `account.person_id`-Werte als kanonische PersonId-Anker uebernehmen. NULL-Personen bleiben ohne Anker.

Unique-Garantien fuer `(anchor_type, anchor_value)` und hoechstens einen aktiven Wert je `(account_id, anchor_type)` pruefen beziehungsweise ergaenzen. Vorhandene PersonId-Unique-Garantie zunaechst als zusaetzliche Absicherung behalten.

Bestandskonflikte muessen den Upgrade sichtbar abbrechen, niemals Daten still ueberschreiben. Kein DB-Reset und kein Fallback auf alte Lookup-Pfade. Bestehende Projektionswerte sind die Quelle fuer den Anker-Backfill; historische Claims enthalten auch fruehere Werte. Keine fiktiven Nachweise oder urspruenglichen Bestaetigungszeitpunkte erfinden; die technische Backfill-Herkunft und Zeitsemantik ausdruecklich dokumentieren.

**Zugriffsgrenze:** Die Migration-Dateisuche lieferte keine zugaenglichen Dateien und meldete Content Exclusions. Schema, aktuelle Migrationsnummer und Upgrade-Testinfrastruktur sind deshalb nicht verifiziert. Vor Umsetzung dieses Pakets ist regulaerer Zugriff durch einen berechtigten Bearbeiter erforderlich. Die Sperre nicht durch andere Tools umgehen.

**Fertig wenn:** Ein Upgrade einer vorhandenen DB unmittelbar dieselben Account-Zuordnungen ueber den neuen PersonId-Anker liefert; NULL-Personen, vorhandene E-Mail-Anker und gespeicherte Quellenwerte unveraendert bleiben.

### 4. `account-persistence`: Account-Schreib- und Lesepfade zusammenfuehren

Abhaengig von 2 und 3.

Dateien: `account/AccountService.kt`, `account/internal/Account.kt`, `account/internal/AccountRepository.kt`, `account/internal/AccountAnchorRepository.kt`, `account/internal/IdentityMatchingService.kt`, `tool_api/ConsolidationStrategy.kt`, `tool_api/AccountDirectory.kt`.

PersonId als `OwnedColumn` aktivieren und gemeinsame Claim-Uebernahme implementieren. Direkte produktive Personenbindung entfernen. Personen- und E-Mail-Komfortoperationen entfernen oder ausschliesslich mit expliziter Provenanz delegieren lassen. Keine erfundenen Tool-Nachweise fuer Seed-Aufrufe. Die noch vorhandenen Journey-/Seed-Aufrufer mit anpassen, soweit fuer Kompilierung und konsistentes Schreiben noetig; die abschliessende Entfernung des redundanten Erfolgsfelds folgt in Paket 5.

Resolver und Reverse-Lookups auf denselben Ankerbestand umstellen. Gleiche Personenbindung ist projektionsseitig idempotent, ein neuer echter Nachweis darf weiter im append-only Log stehen. Eine andere PersonId fuer dasselbe Konto wird abgelehnt. E-Mail-Wechsel ersetzt den alten Anker atomar, auch unter realer Hibernate-Flush-Reihenfolge.

**Fertig wenn:** `AccountServiceTest` und echte DB-Tests beide Attributtypen ueber denselben Pfad abdecken; `IdentityMatchingServiceTest` ohne PersonId-Repository-Sonderweg auskommt; gegensaetzliche Ankertreffer immer abgewiesen werden.

### 5. `journey-integration`: Doppelte PersonId aus dem Erfolgsvertrag entfernen

Abhaengig von 4.

Dateien: `tool_spi/ToolOutcome.kt`, `id_fsc/internal/IdentFscToolHandler.kt`, `id_eid/internal/IdentEidToolHandler.kt`, `orchestrator/journey/JourneyService.kt` sowie betroffene Test-Fixtures und Aufrufer.

Separates `Identified.personId` entfernen; bestehende Handler liefern weiter den Pflicht-Claim mit bisheriger Herkunft und LOA. AdoptIdentity/ConfirmIdentity verwenden die gemeinsame Validierung, Aufloesung und Claim-Uebernahme. Keine eigenstaendigen PersonId-Schreibungen oder doppelte Claim-Logs.

`ToolOutcome.Failed.attemptedPersonId`, tool-interne Personensuche und Drosselung bleiben unveraendert: Sie adressieren gescheiterte Versuche, nicht erfolgreiche Account-Attribute.

Fachliche Konflikte aller Uebernahmepfade auf den bestehenden HTTP-409-Vertrag abbilden. Erwartete Unique-Konflikte bei konkurrierenden Bindungen gezielt behandeln, auch wenn sie erst beim Flush/Commit auftreten; keine pauschale Umdeutung aller `DataIntegrityViolationException`. Unbekannte Integritaetsfehler bleiben sichtbar.

**Fertig wenn:** `Completed.Identified` kein separates PersonId-Feld mehr besitzt; alle produktiven Handler und Test-Fixtures den Pflicht-Claim liefern; neue und bekannte Konten samt Konfliktfaellen durch die gemeinsame Account-Uebernahme laufen.

### 6. `seed-consumers`: Seed und Verbraucher konsistent halten

Abhaengig von 4 und 5.

Dateien: `demo_seed/internal/KcDemoAccountSeeder.kt`, direkt betroffene Modul-KDocs, Keycloak-Sync-/Journey-Protokoll-Verbraucher und Fixtures.

Seed benutzt denselben Claim-/Anker-Pfad mit ausdruecklich als Demo-Bootstrap benannter Herkunft; kein fingierter FSC-/E-Mail-Tool-Lauf. Dafuer `ClaimSource.DEMO_BOOTSTRAP` mit Wert `demo-bootstrap` und Rang `PROVEN` verwenden, ausschliesslich im vorhandenen Demo-Seed. Nicht als Stammdaten-Autoritaet oder echte Sitzungsevidenz behandeln. Neustarts erzeugen keine duplizierten Bootstrap-Claims oder Methoden. Die vorhandene Account-Reihenfolge fuer Keycloak bleibt erhalten.

Keycloak, Profile, E-Mail-Login und Frontend behalten ihre aktuellen Felder und ihre UX. Nur Verbraucher geaenderter interner Typen anpassen. Kontoloeschung muss PersonId-Anker ebenso entfernen wie E-Mail-Anker; vorhandene FK-Cascades erst nach erlaubter Schemapruefung als gegeben behandeln.

**Fertig wenn:** Seed-Neustart idempotent bleibt; `AccountProfile` und Keycloak-Verhalten unveraendert sind; Loeschung beide Ankerarten entfernt.

### 7. `regression-docs`: Verhalten absichern und Zielbild aktualisieren

Abhaengig von 1 bis 6.

Gezielte Tests mit vorhandenen Gradle-/Kotest-/Spring-Werkzeugen:

- Unit: PersonId-Normalisierung, verbindliche Claims, Projektionsregeln, Erstbindung, erneuter gleicher Nachweis, verbotener Personenwechsel, E-Mail-Wechsel und normalisierte Lookups.
- Trust: bestehende Quellenstrings und Rangordnung erhalten; verschiedene Tool-Quellen gleichen Rangs bleiben als Quellen unterscheidbar; falsche Quelle verletzt weiter die Claim-Deklaration. `ClaimSource.trustLevel` haengt nicht vom Claim-LOA ab.
- Voraussetzungen: bestaetigte E-Mail erlaubt weiterhin die bestehenden Gates bis `PROVEN`; unbestaetigte E-Mail und Anforderung `STAMMDATEN` nicht. Angebotsfilter und direkte Aktivierung liefern dasselbe Ergebnis.
- Resolver: PersonId-Staerke bleibt 3 unabhaengig von Claim-Reihenfolge; andere Anker bleiben 2; widerspruechliche eindeutige Kontotreffer werden abgelehnt; Stammdatenpruefung und Mehrdeutigkeit bleiben erhalten.
- Echte DB-Transaktionen: Log/Projektion/Anker gemeinsam committen oder vollstaendig zurueckrollen; konkurrierende Accounts fuer dieselbe PersonId/E-Mail lassen genau einen Besitzer zurueck; E-Mail-Rebind funktioniert unter Unique-Constraints.
- Flows: Registrierung ident-first und enroll-first, spaetere Identifizierung, Re-Identifizierung, falsche Person, Konflikt mit Bestandskonto, Wiederverwendung nach erneutem Login und Bindung eines bisher ungebundenen Matching-Treffers.
- Upgrade: frische und bestehende DB, Konten mit/ohne PersonId, vorhandene E-Mail-Anker, sofort konsistente alte Profil- und neue Anker-Lookups, sichtbarer Abbruch bei inkonsistentem Bestand.
- Verbraucher: Seed-Neustart, unveraenderter Keycloak-Sync, E-Mail-/Passwort-/SMS-Lookup, Drosselung und Loeschung.

Bestehende Testanker: `AccountServiceTest`, `IdentityMatchingServiceTest`, `AnchorTypeTest` (durch Attributregel-Tests ersetzen), `ClaimsTest`, `DefaultAuthPolicyTest`, Handler-Tests fuer FSC/eID, `RegisterEnrollFirstFlowIntegrationTest`, `LoginFlowIntegrationTest`, Account-Loeschtests und `DpopApplicationTests.modulithStructureIsValid`.

Zuerst gezielte `./gradlew test --tests ...`-Selektoren in einem Lauf fuer die geaenderten Bereiche, einschliesslich Test-Kompilierung. Danach passende Integrations- und Modulstrukturtests. Eine Frontend-Pruefung nur bei tatsaechlichen Frontend-Aenderungen. Keine Abhaengigkeiten vorab installieren; `processResources` haengt im bestehenden Build an `npmBuild`.

Dokumentation im passenden Thema anpassen: `docs/ideen/claims-modell-und-vertrauensanker.md` (realer Teilumsetzungsstand, neues Vokabular und Abgrenzung), `docs/06-ablaeufe.md` (Account-Projektion), `docs/04-orchestrierung.md` (Claim-basierte Uebernahme), `docs/12-entscheidungen.md` (ADR-11 auf gemeinsame Ankerregel praezisieren), bei betroffenem Vertrag `docs/07-betrieb.md` und `docs/05-api.md`. `docs/13-review-domaenen-db-modell.md` nur fuer hier tatsaechlich erledigte Befunde aktualisieren. Keine Diagrammaenderungen erforderlich.

**Fertig wenn:** Alle Abnahmekriterien unten nachgewiesen sind. Blockierte Migrationen oder fehlgeschlagene Tests offen melden, nicht als abgeschlossene Implementierung bezeichnen.

## Abnahmekriterien

PersonId und E-Mail werden ausschliesslich durch denselben produktiven Claim-/Anker-Pfad geschrieben und aufgeloest. Es existiert kein separates PersonId-Datenfeld im erfolgreichen ToolOutcome und kein direkter produktiver `findByPersonId`-Lookup als alternative Identitaetsquelle.

Alle bestaetigten Fachregeln bleiben erhalten. Fehler hinterlassen keine teilweise angelegten Konten, Claims oder Anker. Bestandskonten sind nach dem Upgrade ohne erneute Identifizierung aufloesbar. Profile, Keycloak-Synchronisation und bestehende Login-/Registrierungs-UX bleiben kompatibel.

Im produktiven Kotlin-Code gibt es keine Typen `TrustAnchor`, `AnchorClass` oder `AnchorType` mehr. `ClaimSource`, `TrustLevel` und `AttributeType` haben jeweils genau die oben beschriebene Aufgabe; die Quellenrang-Mapping-Funktion wird nicht an mehreren Stellen nachgebaut. Historische Dokumentationsbegriffe und unveraenderte DB-Spaltennamen sind kein Refactoring-Rest.

## Nicht enthalten

Keine Entfernung typisierter Account-Spalten; keine neuen Ankertypen ausser PersonId; keine KVNR-Materialisierung; keine Claims-only-Identifizierung, EUDI-/Wallet-Anbindung, generische Frontend-Identifikatoreingabe, Retraktion, automatische Kontozusammenfuehrung oder vollstaendige rangbasierte Claim-Konsolidierung.

Die dokumentierten spaeteren Ausbaustufen bleiben Zielbild, werden aber nicht als Bestandteil dieser Vereinheitlichung behauptet.

## Tracking und Freigabe

Vor einer freigegebenen Implementierung passende Beads-Aufgaben suchen beziehungsweise anlegen und beanspruchen. Die Ablage dieses Dokuments unter `docs/ideen/` ist eine gesonderte Dokumentationsfreigabe; sie gibt weder die Implementierung noch die zusaetzlichen Terminologievorschlaege frei.

Fuer weitere Aenderungen ausserhalb der freigegebenen atomaren Anlage und KVNR-Aufloesung ist
eine gesonderte Freigabe erforderlich. Die historische Zugriffsgrenze fuer Migrationen bleibt
dokumentiert; dieser Patch aendert keine Migrationen.
