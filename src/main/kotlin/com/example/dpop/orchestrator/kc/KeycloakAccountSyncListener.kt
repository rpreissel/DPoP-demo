package com.example.dpop.orchestrator.kc

import com.example.dpop.account.AccountChanged
import com.example.dpop.account.AccountDeleted
import com.example.dpop.account.AccountProfile
import com.example.dpop.account.AccountService
import com.example.dpop.ext_stammdaten.ExtStammdatenService
import com.example.dpop.ext_stammdaten.PersonData
import com.example.dpop.tool_spi.AttributeType
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * Keeps a Keycloak user mirrored for every orchestrator account
 * - no `infra/tofu/keycloak/main.tf`-declared demo users needed: whatever
 * `KcDemoAccountSeeder` (or any real account creation) does to an account, this reacts to and
 * pushes to Keycloak via [KeycloakAdminClient]. Only registered under the `keycloak` Spring
 * profile - deciding whether this mechanism runs at all is a startup-time concern (`@Profile`),
 * not a runtime toggle.
 *
 * [TransactionPhase.AFTER_COMMIT]: never sync a change that might still roll back, and never hold
 * the account's own DB transaction open across a network call to Keycloak.
 *
 * Best-effort by design: a failed sync is logged, not rethrown - it must never turn an orchestrator
 * account mutation (already committed by the time this runs) into a failed request. A later
 * [AccountChanged] for the same account (or a manual retry) is the recovery path, not an
 * automatic one.
 */
@Component
@Profile("keycloak")
class KeycloakAccountSyncListener(
    private val accountService: AccountService,
    private val extStammdatenService: ExtStammdatenService,
    private val keycloakAdminClient: KeycloakAdminClient,
    private val accountKeypairService: AccountKeypairService,
    private val accountKeycloakKeypairRepository: AccountKeycloakKeypairRepository
) {
    private val log = LoggerFactory.getLogger(KeycloakAccountSyncListener::class.java)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onAccountChanged(event: AccountChanged) {
        val profile = accountService.findAccount(event.accountId) ?: return
        // Nothing worth mirroring yet (REGISTER "Enrollment zuerst" account, freshly created,
        // docs/04-orchestrierung.md) - a Keycloak user needs an email/username; the next
        // AccountChanged once one is confirmed (or the account is identified) syncs it for real.
        if (profile.email == null) return
        // Unidentified account (REGISTER "Enrollment zuerst") - no person to look up yet.
        val person = profile.personId?.let { extStammdatenService.findPersonById(it) }
        try {
            val mirror = kcUserMirror(profile, person, accountService.establishedClaimValues(profile.accountId, MIRRORED_CLAIM_TYPES))
            keycloakAdminClient.upsertUser(
                profile.accountId, profile.email, profile.emailConfirmed,
                mirror.firstName, mirror.lastName, mirror.attributes
            )
            val keypair = accountKeypairService.keypairFor(profile.accountId)
            val activeMethods = profile.activeAuthenticationMethods.map { it.method }.distinct()
            keycloakAdminClient.setPublicKeyCredential(profile.accountId, keypair.publicKeyJwk, activeMethods)
        } catch (e: Exception) {
            log.warn("Keycloak account sync failed for accountId={}", event.accountId, e)
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onAccountDeleted(event: AccountDeleted) {
        try {
            keycloakAdminClient.deleteUser(event.accountId)
        } catch (e: Exception) {
            log.warn("Keycloak account sync (delete) failed for accountId={}", event.accountId, e)
        }
        // Local-only, no Keycloak round-trip needed - safe to do even if the deleteUser() call
        // above failed, unlike deleteUser() itself this can't leave anything orphaned on the
        // Keycloak side. deleteById() would throw if no row exists (e.g. non-keycloak-synced
        // account) - existsById() guard keeps this a true no-op then.
        if (accountKeycloakKeypairRepository.existsById(event.accountId)) {
            accountKeycloakKeypairRepository.deleteById(event.accountId)
        }
    }
}

/**
 * Keycloak's own realm requires a non-blank first/last name on every user - this stands in for
 * an account that has no [com.example.dpop.ext_stammdaten.Person] AND no attested name claims
 * yet (REGISTER "Enrollment zuerst"). Self-healing: recording the `PERSON_ID` anchor or a
 * name-attesting claim (`AccountService.recordClaim`/`recordClaims`) fires its own
 * `AccountChanged`, which re-syncs and overwrites this with the real name the moment one
 * exists - never a value anyone needs to clean up by hand.
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
    AttributeType.STRASSE, AttributeType.HAUSNUMMER, AttributeType.PLZ, AttributeType.ORT
)

/** What one account mirrors into its Keycloak user - shared by the listener's and service's sync paths. */
internal data class KcUserMirror(
    val firstName: String,
    val lastName: String,
    val attributes: Map<String, String>
)

/**
 * Names and attributes for the Keycloak user mirror, per attribute in this precedence: the
 * register person's value when one is bound and has it (PERSON_ID anchor, authoritative
 * stammdaten resolved live via `ext_stammdaten`), then the account's own established claim - a
 * fully attested Interessent (ADR-18: Zuordnung abgelehnt oder noch nie angeboten) carries
 * NAME/VORNAME/GEBURTSDATUM and the address fields as claims even without a register binding.
 * The placeholders remain only for an account that has neither yet. `personId`/`kvnr` attributes
 * stay register-bound by design - an Interessent is visible as the absence of both, never as a
 * hand-maintained status flag.
 */
internal fun kcUserMirror(profile: AccountProfile, person: PersonData?, attested: Map<AttributeType, String>): KcUserMirror =
    KcUserMirror(
        firstName = person?.vorname ?: attested[AttributeType.VORNAME] ?: UNIDENTIFIED_FIRST_NAME,
        lastName = person?.name ?: attested[AttributeType.NAME] ?: UNIDENTIFIED_LAST_NAME,
        attributes = stammdatenAttributes(profile.personId, person, attested)
    )

/**
 * The non-anchor person attributes as plain custom user attributes. `personId`/`kvnr` are
 * resolved live from ext_stammdaten (never cached: they are asserted together with the PERSON_ID
 * anchor, so that anchor alone re-derives them on demand) and exist only for a register-bound
 * account; `geburtsdatum` and the address fields fall back to the account's own attested claim
 * for an Interessent (gap-filling, never overriding a register value). Shared by
 * [KeycloakAccountSyncListener] and [KeycloakAccountSyncService], the two places that already
 * run this exact live lookup.
 */
internal fun stammdatenAttributes(personId: Long?, person: PersonData?, attested: Map<AttributeType, String>): Map<String, String> = buildMap {
    personId?.let { put("personId", it.toString()) }
    person?.kvnr?.let { put("kvnr", it) }
    (person?.geburtsdatum?.toString() ?: attested[AttributeType.GEBURTSDATUM])?.let { put("geburtsdatum", it) }
    (person?.strasse ?: attested[AttributeType.STRASSE])?.let { put("strasse", it) }
    (person?.hausnummer ?: attested[AttributeType.HAUSNUMMER])?.let { put("hausnummer", it) }
    (person?.plz ?: attested[AttributeType.PLZ])?.let { put("plz", it) }
    (person?.ort ?: attested[AttributeType.ORT])?.let { put("ort", it) }
}
