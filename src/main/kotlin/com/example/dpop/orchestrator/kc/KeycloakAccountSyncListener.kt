package com.example.dpop.orchestrator.kc

import com.example.dpop.account.AccountChanged
import com.example.dpop.account.AccountDeleted
import com.example.dpop.account.AccountProfile
import com.example.dpop.account.AccountService
import com.example.dpop.tool_api.PersonMasterData
import com.example.dpop.tool_api.PersonRecord
import com.example.dpop.tool_spi.AttributeType
import java.time.Instant
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.scheduling.annotation.Async

/**
 * Keeps a Keycloak user mirrored for every orchestrator account
 * - no `infra/tofu/keycloak/main.tf`-declared demo users needed: whatever
 * `KcDemoAccountSeeder` (or any real account creation) does to an account, this reacts to and
 * pushes to Keycloak via [KeycloakAdminClient]. Only registered under the `keycloak` Spring
 * profile - deciding whether this mechanism runs at all is a startup-time concern (`@Profile`),
 * not a runtime toggle.
 *
 * [ApplicationModuleListener] rather than a plain `@TransactionalEventListener`: it is Spring
 * Modulith's combination of AFTER_COMMIT, `@Async` and a transaction of its own, and - the reason
 * it is used here - it enrols the event in the **Event Publication Registry**. Before the
 * account's transaction commits, a row is written to `event_publication` recording that this
 * listener still owes work; the row is completed only once this method returns normally.
 *
 * That closes the gap this sync had for a long time. It is best-effort towards the caller by
 * design - a failed sync must never turn an already-committed account change into a failed
 * request - but "best-effort" used to mean the failure left no trace at all: the account had
 * changed, Keycloak did not know, and the only path back was "the account happens to change
 * again", which for an account that never changes again is no path. Now every owed sync is a row
 * that can be listed, retried and counted (docs/07-betrieb.md Abschnitt 3a).
 *
 * AFTER_COMMIT also means the account's own DB transaction is never held open across the network
 * call to Keycloak - the rule `OrchestratorArchitectureTest` enforces for the whole orchestrator.
 *
 * All syncs run one at a time, on the single thread of [KEYCLOAK_SYNC_EXECUTOR] - the explicit
 * `@Async` below takes precedence over the unqualified one inside `@ApplicationModuleListener`.
 * `AccountService` already publishes at most one `AccountChanged` per account and transaction, but
 * two transactions committing close together (two quick requests of the same registration) still
 * deliver two events at once. In parallel both would find no Keycloak user and no keypair yet and
 * both create one: 409 from Keycloak, a primary-key violation on the keypair, a 500 on the
 * credential write. Syncs of different accounts collided too, inside Keycloak (see
 * [KeycloakSyncExecutorConfig]). Serialized, the first creates and the second updates. Every sync
 * reads the account's CURRENT state, so their order does not matter, only that they do not
 * overlap. The single lane is per process - one more reason `DeploymentTopologyCheck` refuses
 * `multiple`.
 */
@Component
@Profile("keycloak")
class KeycloakAccountSyncListener(
    private val accountService: AccountService,
    private val personMasterData: PersonMasterData,
    private val keycloakAdminClient: KeycloakAdminClient,
    private val accountKeypairService: AccountKeypairService,
    private val accountKeycloakKeypairRepository: AccountKeycloakKeypairRepository
) {
    @ApplicationModuleListener
    @Async(KEYCLOAK_SYNC_EXECUTOR)
    fun onAccountChanged(event: AccountChanged) {
        // A change the account could name (the Personenverzeichnis' report, ADR-34) that touches
        // nothing Keycloak mirrors needs no round trip.
        if (event.changed != null && event.changed.none { it in KEYCLOAK_ATTRIBUTE_TYPES }) return
        val profile = accountService.findAccount(event.accountId) ?: return
        // Nothing worth mirroring yet (REGISTER "Enrollment zuerst" account, freshly created,
        // docs/04-orchestrierung.md) - a Keycloak user needs an email/username; the next
        // AccountChanged once one is confirmed (or the account is identified) syncs it for real.
        // An account whose address was withdrawn may still HAVE a mirror, though - it must stop
        // presenting the old address.
        if (profile.email == null) {
            keycloakAdminClient.clearEmail(profile.accountId)
            return
        }
        // Unidentified account (REGISTER "Enrollment zuerst") - no person to look up yet.
        val person = profile.personId?.let { personMasterData.masterDataOf(it) }
        val mirror = kcUserMirror(profile, person, accountService.establishedClaimValues(profile.accountId, MIRRORED_CLAIM_TYPES))
        // Deliberately NOT wrapped in a try/catch any more. A thrown exception is how this method
        // tells the Event Publication Registry "not done" - the publication stays incomplete and is
        // retried. Swallowing it would mark the sync complete and lose it for good, which is
        // exactly the silent state the registry exists to prevent.
        keycloakAdminClient.upsertUser(
            profile.accountId, profile.email, profile.emailConfirmed,
            mirror.firstName, mirror.lastName, mirror.attributes, accountExists = { accountService.findAccount(it) != null }
        )
        val keypair = accountKeypairService.keypairFor(profile.accountId)
        val activeMethods = profile.activeAuthenticationMethods.map { it.method }.distinct()
        keycloakAdminClient.setPublicKeyCredential(profile.accountId, keypair.publicKeyJwk, activeMethods)
    }

    @ApplicationModuleListener
    @Async(KEYCLOAK_SYNC_EXECUTOR)
    fun onAccountDeleted(event: AccountDeleted) {
        // Local first, remote second. The local row can never be orphaned by removing it early
        // (nothing outside this process reads it), while a failed deleteUser() must be retried -
        // and is, because the exception leaves this listener's publication incomplete. On that
        // retry the existsById() guard makes the local half a true no-op.
        if (accountKeycloakKeypairRepository.existsById(event.accountId)) {
            accountKeycloakKeypairRepository.deleteById(event.accountId)
        }
        keycloakAdminClient.deleteUser(event.accountId)
    }
}

/**
 * Keycloak's own realm requires a non-blank first/last name on every user - this stands in for
 * an account that has no [PersonRecord] AND no attested name claims
 * yet (REGISTER "Enrollment zuerst"). Self-healing: every anchor write (the `PERSON_ID` anchor
 * above all) fires `AccountChanged`, which re-syncs and overwrites this with the real name. A
 * name-attesting claim alone does not fire one - it is picked up with the next sync (in practice
 * the anchor write of the same identification) - never a value anyone needs to clean up by hand.
 */
internal const val UNIDENTIFIED_FIRST_NAME = "Unbekannt"
internal const val UNIDENTIFIED_LAST_NAME = "(nicht identifiziert)"

/**
 * The attested claim types both sync paths (event-driven listener, full-reconciliation service)
 * mirror into the Keycloak user: the person attributes an eID attestation can carry. One shared
 * constant so the two paths can never drift apart on what "everything" means.
 */
internal val MIRRORED_CLAIM_TYPES = setOf(
    AttributeType.NAME, AttributeType.VORNAME, AttributeType.GEBURTSDATUM,
    AttributeType.STRASSE, AttributeType.PLZ, AttributeType.ORT
)

/**
 * Every kind of attribute that ends up on the Keycloak user: the mirrored claims plus the
 * identifiers read from the Personenverzeichnis ([stammdatenAttributes]).
 */
internal val KEYCLOAK_ATTRIBUTE_TYPES: Set<AttributeType> = MIRRORED_CLAIM_TYPES + setOf(AttributeType.KVNR, AttributeType.VERSNR)

/** What one account mirrors into its Keycloak user - shared by the listener's and service's sync paths. */
internal data class KcUserMirror(
    val firstName: String,
    val lastName: String,
    val attributes: Map<String, String>
)

/**
 * Names and attributes for the Keycloak user mirror. For an account bound to a person (PERSON_ID
 * anchor) the Personenverzeichnis is the only source, read live - including what it leaves empty:
 * an old attested claim must never resurface after the Personenverzeichnis cleared a field
 * (ADR-34). Only an Interessent (ADR-18: no person bound) is mirrored from its own established
 * claims. The placeholders remain only for an account that has neither yet. `personId`, `kvnr`
 * and `versnr` exist only for a bound account - an Interessent is visible as their absence, never
 * as a hand-maintained status flag.
 */
internal fun kcUserMirror(profile: AccountProfile, person: PersonRecord?, attested: Map<AttributeType, String>): KcUserMirror =
    KcUserMirror(
        firstName = (if (person != null) person.vorname else attested[AttributeType.VORNAME]) ?: UNIDENTIFIED_FIRST_NAME,
        lastName = (if (person != null) person.name else attested[AttributeType.NAME]) ?: UNIDENTIFIED_LAST_NAME,
        attributes = stammdatenAttributes(profile.personId, person, attested)
    )

/**
 * The person attributes as plain custom user attributes - from the Personenverzeichnis for a
 * bound account (live, never cached, never topped up from claims), from the account's own
 * attested claims for an Interessent. Shared by [KeycloakAccountSyncListener] and
 * [KeycloakAccountSyncService], the two places that already run this exact live lookup.
 */
internal fun stammdatenAttributes(personId: String?, person: PersonRecord?, attested: Map<AttributeType, String>): Map<String, String> = buildMap {
    personId?.let { put("personId", it.toString()) }
    if (person != null) {
        person.kvnr?.let { put("kvnr", it) }
        person.versnr?.let { put("versnr", it) }
        person.geburtsdatum?.let { put("geburtsdatum", it.toString()) }
        // One street line - the port already joins the Personenverzeichnis' two fields.
        person.strasse?.let { put("strasse", it) }
        person.plz?.let { put("plz", it) }
        person.ort?.let { put("ort", it) }
    } else {
        attested[AttributeType.GEBURTSDATUM]?.let { put("geburtsdatum", it) }
        attested[AttributeType.STRASSE]?.let { put("strasse", it) }
        attested[AttributeType.PLZ]?.let { put("plz", it) }
        attested[AttributeType.ORT]?.let { put("ort", it) }
    }
}
