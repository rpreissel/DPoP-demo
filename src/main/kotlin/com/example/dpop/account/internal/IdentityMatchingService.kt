package com.example.dpop.account.internal

import com.example.dpop.tool_api.AttributeAuthority
import com.example.dpop.tool_api.ClaimedIdentity
import com.example.dpop.tool_api.IdentityConflictException
import com.example.dpop.tool_api.IdentityResolver
import com.example.dpop.tool_api.MatchedVia
import com.example.dpop.tool_api.PersonDirectory
import com.example.dpop.tool_api.Resolution
import com.example.dpop.tool_api.anchorBindingStrength
import com.example.dpop.tool_api.authority
import com.example.dpop.tool_api.normalizeAnchorValue
import com.example.dpop.tool_api.normalizeKvnr
import com.example.dpop.tool_spi.TrustLevel
import com.example.dpop.tool_spi.AttributeType
import com.example.dpop.tool_spi.Claim
import com.example.dpop.tool_spi.trustLevel
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import java.time.LocalDate

/**
 * The account module's answer to "does an existing account belong to these claims?" - one
 * matching policy (discriminants, normalization, thresholds) for every identification
 * procedure, because the account module owns the data the answer is computed from
 * (docs/ideen/claims-modell-und-vertrauensanker.md, "Identitaetsauflösung & Matching";
 * docs/ideen/account-attribute-und-trust-vereinheitlichen.md, "Gemeinsame Aufloesung").
 *
 * Layer precedence is fixed: unique anchor values first (PERSON_ID ranks highest among them via
 * [AttributeType.anchorBindingStrength] - the same technical `account.anchor` lookup as every
 * other anchor, no separate person_id repository path), then attribute matching. Attribute
 * matching is the only layer that can be ambiguous, and the most expensive error it can
 * make is a false merge, so it never guesses. A future EUDI-Wallet case slots in without a
 * policy fork: an issuer-scoped PID identifier arrives as its own anchor type, and
 * selective disclosure simply shrinks the claim set - a subset can only ever bind weakly.
 */
@Service
class IdentityMatchingService(
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
        val kvnrClaim = claims.firstOrNull { it.attributeType == AttributeType.KVNR }
        val personClaim = claims.firstOrNull { it.attributeType == AttributeType.PERSON_ID }
        val externalPersonId = if (kvnrClaim != null &&
            (personClaim == null || kvnrClaim.source.trustLevel != TrustLevel.STAMMDATEN)
        ) personDirectory.findPersonIdByKvnr(normalizeKvnr(kvnrClaim.value)) else null
        verifyToolAttestedConsistency(claims, externalPersonId)
        resolveByAnchor(claims, externalPersonId.takeIf { personClaim == null })?.let { return it }
        return resolveByAttributeCombination(claims) ?: Resolution.NewInteressent
    }

    /**
     * Tool-attested claims get checked against the stammdaten behind their own kvnr before any
     * matching happens - the tool's word alone doesn't reach the stock. Stammdaten-attested
     * claims (ident-fsc's, EXT_STAMMDATEN) skip this: their anchor IS the stammdaten backend,
     * they were checked at the source. A kvnr that resolves to nobody passes - that's the
     * Interessent case, not a conflict.
     */
    private fun verifyToolAttestedConsistency(claims: Set<Claim>, personId: Long?) {
        val kvnrClaim = claims.firstOrNull { it.attributeType == AttributeType.KVNR } ?: return
        if (kvnrClaim.source.trustLevel == TrustLevel.STAMMDATEN) return
        if (personId == null) return
        val personClaim = claims.firstOrNull { it.attributeType == AttributeType.PERSON_ID }
        if (personClaim != null && personClaim.value.trim().toLong() != personId) {
            throw IdentityConflictException("KVNR und PersonId verweisen auf unterschiedliche Personen")
        }
        val claimed = ClaimedIdentity(
            name = claims.claimValue(AttributeType.NAME),
            vorname = claims.claimValue(AttributeType.VORNAME),
            geburtsdatum = claims.claimValue(AttributeType.GEBURTSDATUM)?.let(LocalDate::parse)
        )
        if (!personDirectory.matchesStammdaten(personId, claimed)) {
            throw IdentityConflictException("Ausweisdaten stimmen nicht mit den angegebenen Daten ueberein")
        }
    }

    /**
     * Layer 1: anchor values - unique, error-free lookups via `account.anchor`'s UNIQUE
     * constraint, PERSON_ID included (anchored exactly like EMAIL,
     * docs/ideen/account-attribute-und-trust-vereinheitlichen.md). KVNR instead resolves live to the external person ID and then to that
     * person's anchor; a historical local KVNR anchor is never consulted.
     * Ranks claims by [AttributeType.anchorBindingStrength] before iterating,
     * so the strongest anchor (PERSON_ID) is always consulted first when several are present -
     * deliberately NOT the claims' [TrustLevel] (docs/ideen/account-attribute-und-trust-
     * vereinheitlichen.md: binding strength and trust level are different axes; a PERSON_ID match
     * outranks an EMAIL match regardless of which tool supplied the claims) and not whatever
     * `Set` implementation a caller happens to pass in (a plain `HashSet` gives no iteration-order
     * guarantee at all).
     */
    private fun resolveByAnchor(claims: Set<Claim>, externalPersonId: Long?): Resolution.ExistingAccount? {
        val matches = mutableListOf<Resolution.ExistingAccount>()
        externalPersonId?.let { personId ->
            accountAnchorRepository.findByAttributeTypeAndValue(AttributeType.PERSON_ID, personId.toString())
                ?.accountId?.let { matches.add(Resolution.ExistingAccount(it, MatchedVia.Anchor(AttributeType.PERSON_ID))) }
        }
        for (claim in claims.sortedByDescending { it.attributeType.anchorBindingStrength ?: 0 }) {
            val attributeType = claim.attributeType.takeIf { it.authority == AttributeAuthority.LOCAL_ANCHOR } ?: continue
            val anchor = accountAnchorRepository.findByAttributeTypeAndValue(
                attributeType,
                attributeType.normalizeAnchorValue(claim.value)
            ) ?: continue
            val accountId = anchor.accountId ?: continue
            matches.add(Resolution.ExistingAccount(accountId, MatchedVia.Anchor(claim.attributeType)))
        }
        if (matches.map { it.accountId }.distinct().size > 1) {
            throw IdentityConflictException("Identitaetsanker verweisen auf unterschiedliche Konten")
        }
        return matches.maxByOrNull { it.matchedVia.bindingStrength }
    }

    /**
     * Layer 2, weakest: normalized attribute matching over the identity log. One real
     * combination today - name + vorname + geburtsdatum, matched in a single sargable query
     * (`ix_attribute_type_value`) - deliberately the most
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
            type1 = AttributeType.NAME,
            value1 = AccountAttribute.normalize(name)!!,
            type2 = AttributeType.VORNAME,
            value2 = AccountAttribute.normalize(vorname)!!,
            type3 = AttributeType.GEBURTSDATUM,
            value3 = AccountAttribute.normalize(geburtsdatum)!!,
            pageable = PageRequest.of(0, CANDIDATE_LIMIT + 1)
        )
        return when {
            candidates.isEmpty() -> null
            candidates.size == 1 -> Resolution.ExistingAccount(
                candidates.first(),
                MatchedVia.Attributes(setOf(AttributeType.NAME, AttributeType.VORNAME, AttributeType.GEBURTSDATUM))
            )
            // candidates.size caps at CANDIDATE_LIMIT + 1 (the page size requested above) - past
            // the ceiling this is a floor, not an exact count, and callers only need "how many"
            // to abort, never the account ids themselves (A6).
            else -> Resolution.Ambiguous(candidates.size)
        }
    }

    private fun Set<Claim>.claimValue(type: AttributeType): String? =
        firstOrNull { it.attributeType == type }?.value
}
