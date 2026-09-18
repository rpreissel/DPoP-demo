package com.example.dpop.account.internal

import com.example.dpop.tool_spi.AttributeType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

/**
 * Append-only identity log; written by `AccountService.recordClaim`, read only by
 * `IdentityMatchingService`'s attribute-matching layer (normalized comparisons against
 * [AccountAttribute.normalizedValue], written once at persist time - see its `@PrePersist`
 * hook - and served entirely from `ix_attribute_type_value`).
 */
@Repository
interface AccountAttributeRepository : JpaRepository<AccountAttribute, Long> {

    /**
     * Account ids whose log contains all three of (type1, value1), (type2, value2),
     * (type3, value3) - a single sargable query instead of three plus an in-memory intersect.
     * [pageable] is a hard candidate ceiling, not real pagination: callers request one page of
     * size N+1 to tell "more than N candidates" apart from "exactly N".
     */
    @Query(
        """
        select a.accountId from AccountAttribute a
        where (a.attributeType = :type1 and a.normalizedValue = :value1)
           or (a.attributeType = :type2 and a.normalizedValue = :value2)
           or (a.attributeType = :type3 and a.normalizedValue = :value3)
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
