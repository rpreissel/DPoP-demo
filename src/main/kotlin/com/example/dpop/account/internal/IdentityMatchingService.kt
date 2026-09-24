package com.example.dpop.account.internal

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
 * layer at all anymore. A future EUDI-Wallet case slots in without a policy fork: an
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
            setOf(AttributeType.NAME, AttributeType.VORNAME, AttributeType.GEBURTSDATUM)
    }

    override fun resolve(claims: Set<Claim>): Resolution {
        val kvnrClaim = claims.firstOrNull { it.attributeType == AttributeType.KVNR }
        val personClaim = claims.firstOrNull { it.attributeType == AttributeType.PERSON_ID }
        val externalPersonId = if (kvnrClaim != null &&
            (personClaim == null || kvnrClaim.source.trustLevel != TrustLevel.STAMMDATEN)
        ) personDirectory.findPersonIdByKvnr(normalizeKvnr(kvnrClaim.value)) else null
        verifyToolAttestedConsistency(claims, externalPersonId)
        return resolveByAnchor(claims, externalPersonId.takeIf { personClaim == null }) ?: Resolution.Unresolved
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
        val attested = accountClaimRepository.findEstablished(accountId).strongestEstablishedValues(ATTESTABLE_IDENTITY_ATTRIBUTES)
        if (attested.isEmpty()) return false
        return personDirectory.matchesStammdaten(
            personId,
            ClaimedIdentity(
                name = attested[AttributeType.NAME],
                vorname = attested[AttributeType.VORNAME],
                geburtsdatum = attested[AttributeType.GEBURTSDATUM]?.let(LocalDate::parse)
            )
        )
    }

    /**
     * Tool-attested claims get checked against the stammdaten behind their own kvnr before any
     * matching happens - the tool's word alone doesn't reach the stock. Stammdaten-attested
     * claims (ident-fsc's, PERSON_DIRECTORY) skip this: their anchor IS the stammdaten backend,
     * they were checked at the source. A kvnr that resolves to nobody passes - that's the
     * Interessent case, not a conflict.
     */
    private fun verifyToolAttestedConsistency(claims: Set<Claim>, personId: String?) {
        val kvnrClaim = claims.firstOrNull { it.attributeType == AttributeType.KVNR } ?: return
        if (kvnrClaim.source.trustLevel == TrustLevel.STAMMDATEN) return
        if (personId == null) return
        val personClaim = claims.firstOrNull { it.attributeType == AttributeType.PERSON_ID }
        if (personClaim != null && personClaim.value.trim() != personId) {
            throw IdentityConflictException(Text("KVNR und PersonId verweisen auf unterschiedliche Personen"))
        }
        val claimed = ClaimedIdentity(
            name = claims.claimValue(AttributeType.NAME),
            vorname = claims.claimValue(AttributeType.VORNAME),
            geburtsdatum = claims.claimValue(AttributeType.GEBURTSDATUM)?.let(LocalDate::parse)
        )
        if (!personDirectory.matchesStammdaten(personId, claimed)) {
            throw IdentityConflictException(Text("Ausweisdaten stimmen nicht mit den angegebenen Daten ueberein"))
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

    private fun Set<Claim>.claimValue(type: AttributeType): String? =
        firstOrNull { it.attributeType == type }?.value
}
