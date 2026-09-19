package com.example.dpop.account.internal

import com.example.dpop.tool_spi.AttributeType
import com.example.dpop.tool_spi.ClaimSource
import com.example.dpop.tool_spi.trustLevel
import java.util.UUID
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

/**
 * Append-only identity log; written by `AccountService.recordClaims`, read only by
 * `IdentityMatchingService`'s attribute-matching layer (normalized comparisons against
 * [AccountClaim.normalizedValue], written once at persist time - see its `@PrePersist` hook -
 * and served entirely from `ix_claim_type_value`).
 */
@Repository
interface AccountClaimRepository : JpaRepository<AccountClaim, Long> {

    /** Everything one method instance ever asserted - the retraction path's only query. */
    fun findByAuthMethodId(authMethodId: UUID): List<AccountClaim>

    /**
     * What this account currently asserts about itself - assertions MINUS retractions, the same
     * `not exists` subtraction [findAccountIdsMatchingAllThree] uses and for the same reason
     * (ADR-12: a withdrawn value must stop counting).
     *
     * The two readers of this are `AccountService`, which keeps the highest trust level per
     * attribute for `AccountProfile.establishedClaims`, and `IdentityMatchingService`, which
     * needs the values themselves to check an attested identity against the register. Ranking
     * stays in Kotlin because the source is deliberately stored untyped (ADR-13), so SQL has
     * nothing to rank it by.
     */
    @Query(
        """
        select a from AccountClaim a
        where a.accountId = :accountId
          and not exists (
              select r.id from AccountRetraction r
              where r.accountId = a.accountId
                and r.attributeType = a.attributeType
                and r.normalizedValue = a.normalizedValue
          )
        """
    )
    fun findEstablished(@Param("accountId") accountId: Long): List<AccountClaim>

    /**
     * Account ids whose log contains all three of (type1, value1), (type2, value2),
     * (type3, value3) - a single sargable query instead of three plus an in-memory intersect.
     * [pageable] is a hard candidate ceiling, not real pagination: callers request one page of
     * size N+1 to tell "more than N candidates" apart from "exactly N".
     *
     * Assertions MINUS retractions (ADR-12): a withdrawn value must stop matching, and the `not
     * exists` is the whole subtraction. It stays cheap because both sides are keyed
     * (attribute_type, normalized_value, account_id) and retractions are rare - if this ever
     * shows up hot, the answer is a maintained projection like `account.anchor`, not a flag on
     * the log.
     */
    @Query(
        """
        select a.accountId from AccountClaim a
        where ((a.attributeType = :type1 and a.normalizedValue = :value1)
            or (a.attributeType = :type2 and a.normalizedValue = :value2)
            or (a.attributeType = :type3 and a.normalizedValue = :value3))
          and not exists (
              select r.id from AccountRetraction r
              where r.accountId = a.accountId
                and r.attributeType = a.attributeType
                and r.normalizedValue = a.normalizedValue
          )
        group by a.accountId
        having count(distinct a.attributeType) = 3
        order by a.accountId
        """
    )
    fun findAccountIdsMatchingAllThree(
        @Param("type1") type1: AttributeType,
        @Param("value1") value1: String,
        @Param("type2") type2: AttributeType,
        @Param("value2") value2: String,
        @Param("type3") type3: AttributeType,
        @Param("value3") value3: String,
        pageable: Pageable
    ): List<Long>
}

/**
 * The strongest surviving value per requested attribute from [AccountClaimRepository.findEstablished]
 * output - trust rank first, recency only breaking ties within a level ("Rangfolge schlaegt
 * Rezenz"). Shared by every established-claims reader (matching in [IdentityMatchingService],
 * the [com.example.dpop.account.AccountService] value facade) so they all select identically.
 */
internal fun List<AccountClaim>.strongestEstablishedValues(types: Set<AttributeType>): Map<AttributeType, String> =
    filter { it.attributeType in types && it.value != null }
        .groupBy { it.attributeType!! }
        .mapValues { (_, claims) ->
            claims.maxWith(
                compareBy<AccountClaim>({ ClaimSource(it.claimSource.orEmpty()).trustLevel.rank }, { it.establishedAt })
            ).value!!
        }
