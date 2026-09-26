package com.example.dpop.account.infrastructure

import com.example.dpop.account.application.IdentityMatchingService
import com.example.dpop.tool_spi.AttributeType
import com.example.dpop.tool_spi.ClaimSource
import com.example.dpop.tool_spi.trustLevel
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

/**
 * Append-only identity log; written by `AccountService.recordClaims`, read via
 * [findEstablished] by every established-claims consumer - the matching guard in
 * [IdentityMatchingService], the [com.example.dpop.account.AccountService] value facade, the
 * account view Keycloak reads (ADR-38) - all comparing against [AccountClaim.normalizedValue], written once at
 * persist time (see its `@PrePersist` hook).
 */
@Repository
interface AccountClaimRepository : JpaRepository<AccountClaim, Long> {

    /** Everything one method instance ever asserted - the retraction path's only query. */
    fun findByAuthMethodId(authMethodId: UUID): List<AccountClaim>

    /**
     * What this account currently asserts about itself - assertions MINUS retractions (ADR-12:
     * a withdrawn value must stop counting).
     *
     * A retraction only cancels assertions made before it (`retractedAt >= establishedAt`):
     * a value re-proven AFTER its own withdrawal counts again - otherwise an e-mail cycle
     * A -> B -> A could never return to A once the A->B replace retracted the old value.
     *
     * The two readers of this are `AccountService`, which keeps the highest trust level per
     * attribute for `AccountProfile.establishedClaims`, and `IdentityMatchingService`, which
     * needs the values themselves to check an attested identity against the register. Ranking
     * stays in Kotlin because the source is deliberately stored untyped (db/migration/KONVENTIONEN.md), so SQL has
     * nothing to rank it by. The change-log check in `AccountService.recordClaims` is a third
     * reader: an already-established (type, value, source) leaves no new log row.
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
                and r.retractedAt >= a.establishedAt
          )
        """
    )
    fun findEstablished(@Param("accountId") accountId: Long): List<AccountClaim>
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
