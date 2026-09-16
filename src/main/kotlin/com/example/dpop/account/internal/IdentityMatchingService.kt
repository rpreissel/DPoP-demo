package com.example.dpop.account.internal

import com.example.dpop.tool_api.ClaimedIdentity
import com.example.dpop.tool_api.IdentityConflictException
import com.example.dpop.tool_api.IdentityResolver
import com.example.dpop.tool_api.MatchedVia
import com.example.dpop.tool_api.PersonDirectory
import com.example.dpop.tool_api.Resolution
import com.example.dpop.tool_api.AnchorType
import com.example.dpop.tool_spi.AnchorClass
import com.example.dpop.tool_spi.AttributeType
import com.example.dpop.tool_spi.Claim
import com.example.dpop.tool_spi.anchorClassOf
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import java.time.LocalDate

/**
 * The account module's answer to "does an existing account belong to these claims?" - one
 * matching policy (discriminants, normalization, thresholds) for every identification
 * procedure, because the account module owns the data the answer is computed from
 * (docs/ideen/claims-modell-und-vertrauensanker.md, "Identitaetsauflösung & Matching").
 *
 * Layer precedence is fixed: the `person_id` projection first, then unique anchor values,
 * then attribute matching - binding strength person_id > anchor > attributes. Attribute
 * matching is the only layer that can be ambiguous, and the most expensive error it can
 * make is a false merge, so it never guesses. A future EUDI-Wallet case slots in without a
 * policy fork: an issuer-scoped PID identifier arrives as its own anchor type (layer 2), and
 * selective disclosure simply shrinks the claim set - a subset can only ever bind weakly.
 */
@Service
class IdentityMatchingService(
    private val accountRepository: AccountRepository,
    private val accountAnchorRepository: AccountAnchorRepository,
    private val accountAttributeRepository: AccountAttributeRepository,
    private val personDirectory: PersonDirectory
) : IdentityResolver {

    companion object {
        /**
         * Hard ceiling on attribute-match candidates, fetched as [CANDIDATE_LIMIT] + 1 so
         * "more than the ceiling" is distinguishable from "exactly the ceiling" without a
         * separate count query.
         */
        private const val CANDIDATE_LIMIT = 50
    }

    override fun resolve(claims: Set<Claim>): Resolution {
        verifyToolAttestedConsistency(claims)
        resolveByPersonIdProjection(claims)?.let { return it }
        resolveByAnchor(claims)?.let { return it }
        return resolveByAttributeCombination(claims) ?: Resolution.NewInteressent
    }

    /**
     * Tool-attested claims get checked against the stammdaten behind their own kvnr before any
     * matching happens - the tool's word alone doesn't reach the stock. Stammdaten-attested
     * claims (ident-fsc's, EXT_STAMMDATEN) skip this: their anchor IS the stammdaten backend,
     * they were checked at the source. A kvnr that resolves to nobody passes - that's the
     * Interessent case, not a conflict.
     */
    private fun verifyToolAttestedConsistency(claims: Set<Claim>) {
        val kvnrClaim = claims.firstOrNull { it.attributeType == AttributeType.KVNR } ?: return
        if (anchorClassOf(kvnrClaim.trustAnchor) == AnchorClass.STAMMDATEN) return
        val personId = personDirectory.findPersonIdByKvnr(kvnrClaim.value) ?: return
        val claimed = ClaimedIdentity(
            name = claims.claimValue(AttributeType.NAME),
            vorname = claims.claimValue(AttributeType.VORNAME),
            geburtsdatum = claims.claimValue(AttributeType.GEBURTSDATUM)?.let(LocalDate::parse)
        )
        if (!personDirectory.matchesStammdaten(personId, claimed)) {
            throw IdentityConflictException("Ausweisdaten stimmen nicht mit den angegebenen Daten ueberein")
        }
    }

    /** Layer 1, strongest: the attested person reference against the account `person_id` projection. */
    private fun resolveByPersonIdProjection(claims: Set<Claim>): Resolution.ExistingAccount? {
        val personId = claims.claimValue(AttributeType.PERSON_ID)?.toLongOrNull() ?: return null
        val account = accountRepository.findByPersonId(personId) ?: return null
        val accountId = account.id ?: return null
        return Resolution.ExistingAccount(accountId, MatchedVia.PersonId(personId))
    }

    /**
     * Layer 2: anchor values - unique, error-free lookups via `account_anchor`'s UNIQUE
     * constraint. Iterates the claims in their attestation order (`toSet()` preserves it), so
     * the strongest anchor attested first is consulted first when several are present.
     */
    private fun resolveByAnchor(claims: Set<Claim>): Resolution.ExistingAccount? {
        for (claim in claims) {
            val anchorType = AnchorType.of(claim.attributeType) ?: continue
            val anchor = accountAnchorRepository.findByAnchorTypeAndValue(
                anchorType.wireName,
                anchorType.normalize(claim.value)
            ) ?: continue
            val accountId = anchor.accountId ?: continue
            return Resolution.ExistingAccount(accountId, MatchedVia.Anchor(claim.attributeType))
        }
        return null
    }

    /**
     * Layer 3, weakest: normalized attribute matching over the identity log. One real
     * combination today - name + vorname + geburtsdatum, matched in a single sargable query
     * (`idx_account_attribute_type_normalized`, migration V34) - deliberately the most
     * discriminant triple the log carries; further combinations (and their rank order) arrive
     * with the procedures that need them. 0 hits falls through to NewInteressent; hitting the
     * candidate ceiling - like more than one hit - is Ambiguous, never a guess: "lieber gar
     * nicht als falsch zusammenführen" doesn't allow softening the ceiling into a best-effort
     * top-N.
     */
    private fun resolveByAttributeCombination(claims: Set<Claim>): Resolution? {
        val name = claims.claimValue(AttributeType.NAME) ?: return null
        val vorname = claims.claimValue(AttributeType.VORNAME) ?: return null
        val geburtsdatum = claims.claimValue(AttributeType.GEBURTSDATUM) ?: return null

        val candidates = accountAttributeRepository.findAccountIdsMatchingAllThree(
            type1 = AttributeType.NAME.wireName,
            value1 = AccountAttribute.normalize(name)!!,
            type2 = AttributeType.VORNAME.wireName,
            value2 = AccountAttribute.normalize(vorname)!!,
            type3 = AttributeType.GEBURTSDATUM.wireName,
            value3 = AccountAttribute.normalize(geburtsdatum)!!,
            pageable = PageRequest.of(0, CANDIDATE_LIMIT + 1)
        )
        return when {
            candidates.isEmpty() -> null
            candidates.size == 1 -> Resolution.ExistingAccount(
                candidates.first(),
                MatchedVia.Attributes(setOf(AttributeType.NAME, AttributeType.VORNAME, AttributeType.GEBURTSDATUM))
            )
            candidates.size > CANDIDATE_LIMIT -> Resolution.Ambiguous(candidates.take(CANDIDATE_LIMIT))
            else -> Resolution.Ambiguous(candidates)
        }
    }

    private fun Set<Claim>.claimValue(type: AttributeType): String? =
        firstOrNull { it.attributeType == type }?.value
}
