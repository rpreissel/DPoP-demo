package com.example.dpop.account.internal

import com.example.dpop.tool_spi.AttributeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

/**
 * The resolve-identity index of the claims model - see [AccountAnchor]. Writes go only
 * through `AccountService` (claims with an anchor role, via `recordClaim`/`recordClaims`); reads back this
 * repository are the generic anchor lookups behind `AccountDirectory.resolveByAnchor` /
 * `anchorValue`.
 */
@Repository
interface AccountAnchorRepository : JpaRepository<AccountAnchor, Long> {
    fun findByAttributeTypeAndValue(attributeType: AttributeType, value: String): AccountAnchor?
    fun findByAccountIdAndAttributeType(accountId: Long, attributeType: AttributeType): AccountAnchor?
}
