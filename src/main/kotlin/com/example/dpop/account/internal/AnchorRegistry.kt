package com.example.dpop.account.internal

import com.example.dpop.account.RetractionAnchor
import com.example.dpop.texts.Text
import com.example.dpop.tool_api.IdentityConflictException
import com.example.dpop.tool_api.anchorRule
import com.example.dpop.tool_api.normalizeAnchorValue
import com.example.dpop.tool_spi.AcrLevel
import com.example.dpop.tool_spi.AttributeType
import org.slf4j.LoggerFactory
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
    private val log = LoggerFactory.getLogger(AnchorRegistry::class.java)

    /**
     * Materializes an anchor row. Idempotent when this account already holds the value. A value
     * held by ANOTHER account is rejected outright ([IdentityConflictException]), never
     * re-assigned: ADR-11 makes a cross-account conflict an upstream rejection, and throwing rolls
     * the whole claim back, log entry included. A new value for THIS account's own anchor
     * re-binds it only if [AnchorRule.allowsReplacement] says so (`EMAIL`); `PERSON_ID` (immutable
     * after first binding) is rejected the same way.
     *
     * A rebind UPDATES the row in place rather than deleting and re-inserting: Hibernate flushes
     * insertions before deletions, so a delete-then-insert pair would briefly hold both rows and
     * trip `ux_anchor_account_type`. The same rebind also RETRACTS the old value from the claim
     * log (`ACCOUNT_MANAGEMENT`): the log has to agree with the anchor (ADR-19) instead of keeping
     * a replaced value established forever.
     *
     * Both writes are priced separately by [AnchorRule.acrFloor] and refused below it -
     * establishing binds a value to an account, replacing re-points an account that other people's
     * lookups already resolve through, which is the write worth protecting.
     */
    fun bind(accountId: Long, type: AttributeType, value: String, establishedAt: Instant, provenAcr: AcrLevel) {
        val anchor = checkNotNull(type.anchorRule) { "$type is not a local anchor attribute" }
        val normalized = type.normalizeAnchorValue(value)
        accountAnchorRepository.findByAttributeTypeAndValue(type, normalized)?.let { held ->
            if (held.accountId == accountId) return
            log.warn(
                "Anchor conflict: {} anchor already held by account {}, rejected for account {}",
                type.wireName, held.accountId, accountId
            )
            throw IdentityConflictException(Text("Dieser {type}-Wert gehoert bereits zu einem anderen Konto", "type" to type.wireName))
        }
        val existing = accountAnchorRepository.findByAccountIdAndAttributeType(accountId, type)
        if (existing != null) {
            if (!anchor.allowsReplacement) {
                log.warn(
                    "Anchor conflict: {} for account {} is immutable, already bound to {}, rejected new value",
                    type.wireName, accountId, existing.value
                )
                throw IdentityConflictException(Text("Dieser {type}-Wert kann fuer dieses Konto nicht mehr geaendert werden", "type" to type.wireName))
            }
            requireAnchorAcr(type, provenAcr, floor = anchor.acrFloor.replace, replacing = true)
            // ADR-19: the replaced value lapses. Not when old and new are the same value in the
            // log's terms: a case-preserving anchor (eID restricted_id) can be replaced by a value
            // differing only in case, and the retraction - stamped with the new claim's own time -
            // would then also void the claim just being set (review 2026-09, Phase F).
            val replacedLogValue = AccountClaim.normalize(type, checkNotNull(existing.value))
            if (replacedLogValue != AccountClaim.normalize(type, value)) {
                claimLedger.retract(accountId, type, replacedLogValue, RetractionAnchor.ACCOUNT_MANAGEMENT, "anker-ersetzt", establishedAt)
            }
            existing.value = normalized
            existing.establishedAcr = provenAcr.value
            existing.establishedAt = establishedAt
            accountAnchorRepository.save(existing)
            return
        }
        requireAnchorAcr(type, provenAcr, floor = anchor.acrFloor.establish, replacing = false)
        accountAnchorRepository.save(
            AccountAnchor(
                accountId = accountId,
                attributeType = type,
                value = normalized,
                establishedAcr = provenAcr.value,
                establishedAt = establishedAt
            )
        )
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

    /**
     * Refuses an anchor write the session has not paid for. Rejecting rather than silently logging
     * the claim without its anchor: a caller that believed it bound an identity must not proceed on
     * a false premise (ADR-11's line - reject, never quietly skip).
     */
    private fun requireAnchorAcr(type: AttributeType, provenAcr: AcrLevel, floor: AcrLevel, replacing: Boolean) {
        if (AcrLevel.rank(provenAcr) >= AcrLevel.rank(floor)) return
        log.warn(
            "Anchor floor: {} may only be {} at {} or above, session proved {}",
            type.wireName, if (replacing) "replaced" else "set", floor.value, provenAcr.value
        )
        throw IdentityConflictException(
            // Two sentences, not one with the verb as a value: a verb is wording, every language inflects it itself.
            if (replacing) {
                Text("Dieser {type}-Wert kann erst ab {floor} ersetzt werden, nachgewiesen ist {provenAcr}", "type" to type.wireName, "floor" to floor.value, "provenAcr" to provenAcr.value)
            } else {
                Text("Dieser {type}-Wert kann erst ab {floor} gesetzt werden, nachgewiesen ist {provenAcr}", "type" to type.wireName, "floor" to floor.value, "provenAcr" to provenAcr.value)
            }
        )
    }
}
