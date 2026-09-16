package com.example.dpop.account.internal

import com.example.dpop.tool_api.AnchorType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

/**
 * The resolve-identity index of the claims model - see [AccountAnchor]. Writes go only
 * through `AccountService` (claims with an anchor role, `confirmEmail`); reads back this
 * repository are the generic anchor lookups behind `AccountDirectory.resolveByAnchor` /
 * `anchorValue`.
 */
@Repository
interface AccountAnchorRepository : JpaRepository<AccountAnchor, Long> {
    fun findByAnchorTypeAndValue(anchorType: AnchorType, value: String): AccountAnchor?
    fun findByAccountIdAndAnchorType(accountId: Long, anchorType: AnchorType): AccountAnchor?
    fun existsByAnchorTypeAndValue(anchorType: AnchorType, value: String): Boolean
}
