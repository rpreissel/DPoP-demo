package com.example.dpop.orchestrator.kc

import com.example.dpop.account.AccountProfile
import com.example.dpop.account.AccountService
import com.example.dpop.tool_api.PersonMasterData
import com.example.dpop.tool_api.PersonRecord
import com.example.dpop.tool_spi.AttributeType
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * An account as Keycloak sees it - read through on every lookup, never copied into Keycloak
 * (review 2026-09, P-3; fahrplan Phase E 26). Keycloak's user federation (`OrchestratorStorageProvider`
 * in the extension) asks for it by account id, email or username; the answer is live, so there is
 * no second copy that could drift, and no sync that has to touch every account (10 million+).
 *
 * The username is the confirmed email where there is one, else `account-<id>` - stable for an
 * account without an address, and never a value Keycloak has to invent.
 */
data class KcAccountView(
    val accountId: Long,
    val username: String,
    val email: String?,
    val emailVerified: Boolean,
    val firstName: String,
    val lastName: String,
    /** `orchestratorAccountId` plus the person attributes behind the token claims (person_id, versnr, ...). */
    val attributes: Map<String, String>,
    /** The account's public key for the account-token grant (ADR-9), once the orchestrator has minted one. */
    val publicKeyJwk: String?,
    /** The account's active methods - what the grant used to read from the uploaded credential. */
    val authMethods: List<String>,
)

@Component
@Transactional(readOnly = true)
class KcAccountViews(
    private val accountService: AccountService,
    private val personMasterData: PersonMasterData,
    private val keypairs: AccountKeycloakKeypairRepository,
) {
    fun byAccountId(accountId: Long): KcAccountView? = accountService.findAccount(accountId)?.let(::viewOf)

    fun byEmail(email: String): KcAccountView? =
        accountService.resolveByAnchor(AttributeType.EMAIL, email.trim())?.let(::byAccountId)

    /** `account-<id>` or an email - the two forms [KcAccountView.username] takes. */
    fun byUsername(username: String): KcAccountView? =
        username.removePrefix(USERNAME_PREFIX).takeIf { it != username }?.toLongOrNull()?.let(::byAccountId)
            ?: byEmail(username)

    private fun viewOf(profile: AccountProfile): KcAccountView {
        val person = profile.personId?.let { personMasterData.masterDataOf(it) }
        val names = kcUserMirror(profile, person, accountService.establishedClaimValues(profile.accountId, MIRRORED_CLAIM_TYPES))
        return KcAccountView(
            accountId = profile.accountId,
            username = profile.email ?: "$USERNAME_PREFIX${profile.accountId}",
            email = profile.email,
            emailVerified = profile.emailConfirmed,
            firstName = names.firstName,
            lastName = names.lastName,
            attributes = names.attributes + (ACCOUNT_ID_ATTRIBUTE to profile.accountId.toString()),
            publicKeyJwk = keypairs.findById(profile.accountId).orElse(null)?.publicKeyJwk,
            authMethods = profile.activeAuthenticationMethods.map { it.method }.distinct(),
        )
    }

    companion object {
        const val USERNAME_PREFIX = "account-"
        const val ACCOUNT_ID_ATTRIBUTE = "orchestratorAccountId"
    }
}

/**
 * Keycloak's own realm requires a non-blank first/last name on every user - this stands in for
 * an account that has no [PersonRecord] AND no attested name claims
 * yet (REGISTER "Enrollment zuerst"). Read live on every lookup ([KcAccountViews]), so the real
 * name replaces it as soon as the account has one - nothing to clean up.
 */
internal const val UNIDENTIFIED_FIRST_NAME = "Unbekannt"
internal const val UNIDENTIFIED_LAST_NAME = "(nicht identifiziert)"

/**
 * The attested claim types Keycloak shows for an account without a bound person: the person
 * attributes an eID attestation can carry.
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

/** Names and attributes of one account as Keycloak shows them. */
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
 * attested claims for an Interessent.
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
