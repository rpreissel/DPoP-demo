package com.example.dpop.account.internal

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

/**
 * Append-only identity log; written by `AccountService.recordClaim`, read only by
 * `IdentityMatchingService`'s attribute-matching layer (normalized comparisons - the log keeps
 * raw values, normalization happens in the query).
 */
@Repository
interface AccountAttributeRepository : JpaRepository<AccountAttribute, Long> {

    /** Account ids whose log contains [type] with the given value, compared lower-cased and trimmed. */
    @Query("select a.accountId from AccountAttribute a where a.attributeType = :type and lower(trim(a.value)) = lower(trim(:value))")
    fun findAccountIdsByTypeAndNormalizedValue(type: String, value: String): List<Long?>
}
