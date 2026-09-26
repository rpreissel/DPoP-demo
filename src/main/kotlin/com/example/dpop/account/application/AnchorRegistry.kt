package com.example.dpop.account.application

import com.example.dpop.account.infrastructure.AccountAnchor
import com.example.dpop.account.infrastructure.AccountAnchorRepository
import com.example.dpop.account.domain.AnchorDecision
import com.example.dpop.account.RetractionAnchor
import com.example.dpop.tool_api.anchorRule
import com.example.dpop.tool_api.normalizeAnchorValue
import com.example.dpop.tool_spi.AcrLevel
import com.example.dpop.tool_spi.AttributeType
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * The account's anchors: the locally owned identifiers (`PERSON_ID`, `EID_RESTRICTED_ID`, `EMAIL`,
 * ...) an account is found by - one row per account and type, one account per value. Split off
 * `AccountService` (review 2026-09-26, A-6), which stays the facade and takes the account lock
 * before calling [bind]; the claim log is [ClaimLedger]'s.
 */
@Component
class AnchorRegistry(
    private val accountAnchorRepository: AccountAnchorRepository,
    private val claimLedger: ClaimLedger,
) {
    /**
     * Materializes an anchor row as [AnchorDecision] decides (ADR-11, ADR-19, ADR-5 floors). The
     * caller holds the account lock (`AccountService.recordClaims`).
     */
    fun bind(accountId: Long, type: AttributeType, value: String, establishedAt: Instant, provenAcr: AcrLevel) {
        val rule = checkNotNull(type.anchorRule) { "$type is not a local anchor attribute" }
        val normalized = type.normalizeAnchorValue(value)
        val existing = accountAnchorRepository.findByAccountIdAndAttributeType(accountId, type)
        val decision = AnchorDecision.decide(
            accountId, type, value,
            heldBy = accountAnchorRepository.findByAttributeTypeAndValue(type, normalized)?.accountId,
            currentValue = existing?.value,
            rule = rule,
            provenAcr = provenAcr,
        )
        when (decision) {
            AnchorDecision.AlreadyHeld -> Unit
            AnchorDecision.Establish -> accountAnchorRepository.save(
                AccountAnchor(accountId = accountId, attributeType = type, value = normalized, establishedAcr = provenAcr.value, establishedAt = establishedAt)
            )
            // Updated in place rather than deleted and re-inserted: Hibernate flushes insertions
            // before deletions, so the pair would briefly hold two rows and trip ux_anchor_account_type.
            is AnchorDecision.Replace -> {
                decision.retractFromLog?.let {
                    claimLedger.retract(accountId, type, it, RetractionAnchor.ACCOUNT_MANAGEMENT, "anker-ersetzt", establishedAt)
                }
                val row = checkNotNull(existing)
                row.value = normalized
                row.establishedAcr = provenAcr.value
                row.establishedAt = establishedAt
                accountAnchorRepository.save(row)
            }
        }
    }

    /** The account holding [value] as its [type] anchor, or `null`. */
    fun holderOf(type: AttributeType, value: String): Long? =
        accountAnchorRepository.findByAttributeTypeAndValue(type, type.normalizeAnchorValue(value))?.accountId

    /** This account's [type] anchor value, or `null`. */
    fun valueOf(accountId: Long, type: AttributeType): String? =
        accountAnchorRepository.findByAccountIdAndAttributeType(accountId, type)?.value

    fun anchorsOf(accountId: Long): List<AccountAnchor> = accountAnchorRepository.findByAccountId(accountId)

    /** Deletes this account's [type] anchor, if any (ADR-12: nothing resolves the account by it afterwards). */
    fun remove(accountId: Long, type: AttributeType) {
        accountAnchorRepository.findByAccountIdAndAttributeType(accountId, type)?.let { accountAnchorRepository.delete(it) }
    }

    /**
     * Releases [anchors] and flushes at once: Hibernate orders insertions before deletions within
     * one flush, so writing the same values on another account in the same transaction would
     * otherwise trip `ux_anchor_value` (the same ordering trap [bind]'s in-place rebind documents).
     */
    fun releaseNow(anchors: List<AccountAnchor>) {
        accountAnchorRepository.deleteAll(anchors)
        accountAnchorRepository.flush()
    }
}
