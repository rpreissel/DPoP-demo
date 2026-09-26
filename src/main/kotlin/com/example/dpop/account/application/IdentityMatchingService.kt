package com.example.dpop.account.application

import com.example.dpop.account.infrastructure.strongestEstablishedValues
import com.example.dpop.account.domain.passportForm
import com.example.dpop.account.infrastructure.AccountAnchorRepository
import com.example.dpop.account.infrastructure.AccountClaimRepository
import com.example.dpop.texts.Text
import com.example.dpop.tool_api.ClaimedIdentity
import com.example.dpop.tool_api.IdentityConflictException
import com.example.dpop.tool_api.IdentityResolver
import com.example.dpop.tool_api.MatchedVia
import com.example.dpop.tool_api.PersonDirectory
import com.example.dpop.tool_api.Resolution
import com.example.dpop.tool_api.normalizeAnchorValue
import com.example.dpop.tool_api.normalizeKvnr
import com.example.dpop.tool_api.anchorRule
import com.example.dpop.tool_api.isLocalAnchor
import com.example.dpop.tool_spi.TrustLevel
import com.example.dpop.tool_spi.AttributeType
import com.example.dpop.tool_spi.Claim
import com.example.dpop.tool_spi.trustLevel
import org.springframework.stereotype.Service
import java.time.LocalDate

/**
 * The account module's answer to "does an existing account belong to these claims?" - one
 * matching policy (discriminants, normalization, thresholds) for every identification
 * procedure, because the account module owns the data the answer is computed from
 * (docs/archiv/claims-modell-und-vertrauensanker.md, "Identitaetsauflösung & Matching";
 * docs/ideen/account-attribute-und-trust-vereinheitlichen.md, "Gemeinsame Aufloesung").
 *
 * Resolution runs on anchors only (ADR-19): unique lookups via `account.anchor`'s UNIQUE
 * constraint, PERSON_ID included via [AnchorRule.bindingStrength] - the same technical lookup
 * as every other anchor, no separate person_id repository path. The tool-attested identity
 * data behind a KVNR is checked against the register ([verifyToolAttestedConsistency]),
 * never matched against the account stock: attribute combinations are ambiguous by nature,
 * and the most expensive error they can make is a false merge, so they are not a resolution
 * layer at all. A future EUDI-Wallet case slots in without a policy fork: an
 * issuer-scoped PID identifier arrives as its own anchor type, and selective disclosure
 * simply shrinks the claim set - a subset can only ever bind weakly.
 */
@Service
class IdentityMatchingService(
    private val accountAnchorRepository: AccountAnchorRepository,
    private val accountClaimRepository: AccountClaimRepository,
    private val personDirectory: PersonDirectory
) : IdentityResolver {

    companion object {
        /** What an attestation can establish and the register can be checked against. */
        private val ATTESTABLE_IDENTITY_ATTRIBUTES =
            setOf(AttributeType.FAMILY_NAME, AttributeType.GIVEN_NAMES, AttributeType.BIRTH_DATE)

        /**
         * What tells two register persons apart whose name and date of birth are the same
         * ([PersonDirectory.hasNamesake], ADR-18, addendum 2026-09-26): then the attested address has
         * to match too. An attestation without one (a passport read) or with a moved address goes on
         * to the Freischaltcode letter.
         */
        private val ADDRESS_ATTRIBUTES = setOf(AttributeType.STREET_ADDRESS, AttributeType.POSTAL_CODE, AttributeType.LOCALITY)
    }

    override fun resolve(claims: Set<Claim>): Resolution {
        val kvnrClaim = claims.firstOrNull { it.attributeType == AttributeType.KVNR }
        val personClaim = claims.firstOrNull { it.attributeType == AttributeType.PERSON_ID }
        // A KVNR here always comes from the Personenverzeichnis itself (ToolHandlerRegistry refuses
        // any other source at startup) - it was checked at the source, so it only resolves the person
        // when the claims carry no person reference of their own.
        val externalPersonId = if (kvnrClaim != null && personClaim == null) {
            personDirectory.findPersonIdByKvnr(normalizeKvnr(kvnrClaim.value))
        } else {
            null
        }
        return resolveByAnchor(claims, externalPersonId) ?: Resolution.Unresolved
    }

    /**
     * The account-scoped counterpart of [verifyToolAttestedConsistency]: there, the attested
     * identity and the kvnr arrive in the SAME claim set; here the attestation already happened
     * in an earlier step and lives on the account, while only the person reference is new.
     *
     * Per attribute the strongest surviving claim wins, recency only breaking ties within a
     * trust level ("Rangfolge schlägt Rezenz", docs/archiv/claims-modell-und-vertrauensanker.md).
     */
    override fun attestedIdentityMatches(accountId: Long, personId: String): Boolean {
        val attested = accountClaimRepository.findEstablished(accountId)
            .strongestEstablishedValues(ATTESTABLE_IDENTITY_ATTRIBUTES + ADDRESS_ATTRIBUTES)
        // All of them, not "whatever was attested": ClaimedIdentity skips a null field by design, so
        // a missing date of birth would quietly fall back to the name alone.
        if (!attested.keys.containsAll(ATTESTABLE_IDENTITY_ATTRIBUTES)) return false
        val withAddress = personDirectory.hasNamesake(personId)
        if (withAddress && !attested.keys.containsAll(ADDRESS_ATTRIBUTES)) return false
        return personDirectory.matchesMasterData(
            personId,
            ClaimedIdentity(
                familyName = attested.getValue(AttributeType.FAMILY_NAME),
                givenNames = attested.getValue(AttributeType.GIVEN_NAMES),
                birthDate = LocalDate.parse(attested.getValue(AttributeType.BIRTH_DATE)),
                streetAddress = attested[AttributeType.STREET_ADDRESS].takeIf { withAddress },
                postalCode = attested[AttributeType.POSTAL_CODE].takeIf { withAddress },
                locality = attested[AttributeType.LOCALITY].takeIf { withAddress }
            )
        )
    }

    override fun attestationFits(accountId: Long, claims: Set<Claim>): Boolean {
        val attested = accountClaimRepository.findEstablished(accountId).strongestEstablishedValues(ATTESTABLE_IDENTITY_ATTRIBUTES)
        return ATTESTABLE_IDENTITY_ATTRIBUTES.all { type ->
            val before = attested[type] ?: return@all true
            val now = claims.firstOrNull { it.attributeType == type }?.value ?: return@all true
            passportForm(before) == passportForm(now)
        }
    }

    /**
     * Anchor values - unique, error-free lookups via `account.anchor`'s UNIQUE constraint,
     * PERSON_ID included. KVNR instead resolves live to the external person ID and then to that
     * person's anchor; a historical local KVNR anchor is never consulted. Ranks claims by
     * [AnchorRule.bindingStrength] before iterating, so the strongest anchor (PERSON_ID) is
     * always consulted first - deliberately NOT the claims' [TrustLevel] (a different axis: a
     * PERSON_ID match outranks an EMAIL match regardless of which tool supplied the claims) and
     * not whatever `Set` implementation a caller happens to pass in (a plain `HashSet` gives no
     * iteration-order guarantee at all).
     */
    private fun resolveByAnchor(claims: Set<Claim>, externalPersonId: String?): Resolution.ExistingAccount? {
        val matches = mutableListOf<Resolution.ExistingAccount>()
        externalPersonId?.let { personId ->
            accountAnchorRepository.findByAttributeTypeAndValue(AttributeType.PERSON_ID, personId)
                ?.accountId?.let { matches.add(Resolution.ExistingAccount(it, MatchedVia.Anchor(AttributeType.PERSON_ID))) }
        }
        for (claim in claims
            .filter { it.attributeType.isLocalAnchor }
            .sortedByDescending { checkNotNull(it.attributeType.anchorRule) { "${it.attributeType} has no anchor rule" }.bindingStrength }
        ) {
            val anchor = accountAnchorRepository.findByAttributeTypeAndValue(
                claim.attributeType,
                claim.attributeType.normalizeAnchorValue(claim.value)
            ) ?: continue
            val accountId = anchor.accountId ?: continue
            matches.add(Resolution.ExistingAccount(accountId, MatchedVia.Anchor(claim.attributeType)))
        }
        if (matches.map { it.accountId }.distinct().size > 1) {
            throw IdentityConflictException(Text("Identitaetsanker verweisen auf unterschiedliche Konten"))
        }
        return matches.maxByOrNull { it.matchedVia.bindingStrength }
    }
}
