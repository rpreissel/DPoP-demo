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
)

@Component
@Transactional(readOnly = true)
class KcAccountViews(
    private val accountService: AccountService,
    private val personMasterData: PersonMasterData,
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
    AttributeType.FAMILY_NAME, AttributeType.GIVEN_NAMES, AttributeType.BIRTH_DATE,
    AttributeType.STREET_ADDRESS, AttributeType.POSTAL_CODE, AttributeType.LOCALITY
)

/**
 * Every kind of attribute that ends up on the Keycloak user: the mirrored claims plus the
 * identifiers read from the Personenverzeichnis ([masterDataAttributes]).
 */
internal val KEYCLOAK_ATTRIBUTE_TYPES: Set<AttributeType> = MIRRORED_CLAIM_TYPES + setOf(AttributeType.KVNR, AttributeType.INSURANCE_NUMBER)

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
        firstName = (if (person != null) person.givenNames else attested[AttributeType.GIVEN_NAMES]) ?: UNIDENTIFIED_FIRST_NAME,
        lastName = (if (person != null) person.familyName else attested[AttributeType.FAMILY_NAME]) ?: UNIDENTIFIED_LAST_NAME,
        attributes = masterDataAttributes(profile.personId, person, attested)
    )

/**
 * The person attributes as plain custom user attributes - from the Personenverzeichnis for a
 * bound account (live, never cached, never topped up from claims), from the account's own
 * attested claims for an Interessent.
 */
internal fun masterDataAttributes(personId: String?, person: PersonRecord?, attested: Map<AttributeType, String>): Map<String, String> = buildMap {
    personId?.let { put("personId", it.toString()) }
    if (person != null) {
        person.kvnr?.let { put("kvnr", it) }
        person.insuranceNumber?.let { put("versnr", it) }
        person.birthDate?.let { put("birthDate", it.toString()) }
        // One street line - the port already joins the Personenverzeichnis' two fields.
        person.streetAddress?.let { put("streetAddress", it) }
        person.postalCode?.let { put("postalCode", it) }
        person.locality?.let { put("locality", it) }
    } else {
        attested[AttributeType.BIRTH_DATE]?.let { put("birthDate", it) }
        attested[AttributeType.STREET_ADDRESS]?.let { put("streetAddress", it) }
        attested[AttributeType.POSTAL_CODE]?.let { put("postalCode", it) }
        attested[AttributeType.LOCALITY]?.let { put("locality", it) }
    }
}
