package com.example.dpop.account.application

import com.example.dpop.account.infrastructure.strongestEstablishedValues
import com.example.dpop.account.infrastructure.AccountClaim
import com.example.dpop.account.infrastructure.AccountClaimRepository
import com.example.dpop.account.infrastructure.AccountRetraction
import com.example.dpop.account.infrastructure.AccountRetractionRepository
import com.example.dpop.account.domain.ClaimKey
import com.example.dpop.account.domain.normalizeClaimValue
import com.example.dpop.account.RetractionAnchor
import com.example.dpop.tool_api.AttributeAuthority
import com.example.dpop.tool_api.authority
import com.example.dpop.tool_spi.AcrLevel
import com.example.dpop.tool_spi.AttributeType
import com.example.dpop.tool_spi.Claim
import com.example.dpop.tool_spi.ClaimSource
import com.example.dpop.tool_spi.TrustLevel
import com.example.dpop.tool_spi.trustLevel
import com.example.dpop.tool_spi.validateValue
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * The account's claim log (docs/archiv/claims-modell-und-vertrauensanker.md): what was asserted
 * about the account, by which source and method, and what was withdrawn again (ADR-12). Split off
 * `AccountService` (review 2026-09-26, A-6), which stays the facade and decides locking and order;
 * this class only appends and reads. Anchors are [AnchorRegistry]'s.
 *
 * Every withdrawal goes through [retract], so none escapes the change log (ADR-39).
 */
@Component
class ClaimLedger(
    private val accountClaimRepository: AccountClaimRepository,
    private val accountRetractionRepository: AccountRetractionRepository,
    private val changeLog: ChangeLog,
) {

    /**
     * Appends [claims] a single completed tool run asserted. Never overwrites a prior claim; the
     * log is provenance. At most one claim per [AttributeType]. A claim whose (type, value, source,
     * method) is already established leaves no new row - the log is a change log, not a run log;
     * the method instance is part of the key so a fresh enrollment of a known value still logs.
     *
     * [provenAcr] caps what a claim establishes: an enrollment from a loa1 session establishes at
     * loa1 even if the tool's own ceiling is loa2 (ADR-5).
     *
     * @return each claim with the instant it was established at, in order - the anchor write
     *   that may follow uses the same instant.
     */
    fun append(accountId: Long, claims: List<Claim>, provenAcr: AcrLevel, authMethodId: UUID?): List<Pair<Claim, Instant>> {
        val seen = mutableSetOf<AttributeType>()
        claims.forEach { claim ->
            claim.validateValue()
            check(seen.add(claim.attributeType)) {
                "recordClaims($accountId): more than one claim for ${claim.attributeType.wireName}"
            }
        }
        val logged = accountClaimRepository.findEstablished(accountId)
            .map { claim ->
                ClaimKey(
                    checkNotNull(claim.attributeType),
                    checkNotNull(claim.normalizedValue),
                    checkNotNull(claim.claimSource),
                    claim.authMethodId
                )
            }
            .toMutableSet()
        return claims.map { claim ->
            val establishedAt = Instant.now()
            if (logged.add(
                    ClaimKey(
                        claim.attributeType,
                        checkNotNull(normalizeClaimValue(claim.attributeType, claim.value)),
                        claim.source.value,
                        authMethodId
                    )
                )
            ) {
                accountClaimRepository.save(
                    AccountClaim(
                        accountId = accountId,
                        attributeType = claim.attributeType,
                        value = claim.value,
                        claimSource = claim.source.value,
                        // What was actually proven, not what the tool can reach at most (ADR-5;
                        // review 2026-09, Phase F - the log used to keep the uncapped tool value).
                        establishedAcr = (claim.establishedAcr?.let { AcrLevel.min(it, provenAcr) } ?: provenAcr).value,
                        authMethodId = authMethodId,
                        establishedAt = establishedAt
                    )
                )
            }
            claim to establishedAt
        }
    }

    /** Assertions minus retractions - the one view every reader uses. */
    fun established(accountId: Long): List<AccountClaim> = accountClaimRepository.findEstablished(accountId)

    /** The established VALUES for [types], strongest assertion per attribute. */
    fun establishedValues(accountId: Long, types: Set<AttributeType>): Map<AttributeType, String> =
        established(accountId).strongestEstablishedValues(types)

    /** The same, counting only sources that PROVE the value - self-reported ones never do. */
    fun provenValues(accountId: Long, types: Set<AttributeType>): Map<AttributeType, String> =
        established(accountId)
            .filter { ClaimSource(it.claimSource.orEmpty()).trustLevel.rank >= TrustLevel.PROVEN.rank }
            .strongestEstablishedValues(types)

    /** Highest [TrustLevel] per established attribute. */
    fun establishedTrust(accountId: Long): Map<AttributeType, TrustLevel> =
        established(accountId)
            .mapNotNull { claim ->
                val type = claim.attributeType ?: return@mapNotNull null
                val source = claim.claimSource?.let(::ClaimSource) ?: return@mapNotNull null
                type to source.trustLevel
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, levels) -> levels.maxBy { it.rank } }

    /** What method instance [instanceId] asserted that belongs to its method module, as (type, value). */
    fun ownedBy(accountId: Long, instanceId: UUID): Set<Pair<AttributeType, String?>> =
        accountClaimRepository.findByAuthMethodId(instanceId)
            .filter { it.accountId == accountId && it.attributeType?.authority == AttributeAuthority.MethodModule }
            .mapNotNull { claim -> claim.attributeType?.let { it to claim.normalizedValue } }
            .toSet()

    /**
     * Withdraws every established value of [attributeType] - one retraction row per distinct value.
     * @return true if something was established and is now withdrawn.
     */
    fun retractEstablished(accountId: Long, attributeType: AttributeType, trustAnchor: RetractionAnchor, reason: String?, at: Instant): Boolean {
        val established = established(accountId)
            .filter { it.attributeType == attributeType }
            .map { it.normalizedValue }
            .distinct()
        established.forEach { retract(accountId, attributeType, it, trustAnchor, reason, at) }
        return established.isNotEmpty()
    }

    /** One withdrawal, logged in the change log; the value stays in the retraction row, which goes with the account. */
    fun retract(accountId: Long, type: AttributeType, normalizedValue: String?, trustAnchor: RetractionAnchor, reason: String?, at: Instant) {
        changeLog.attributeRetracted(accountId, type.name, trustAnchor = trustAnchor.name, reason = reason, at = at)
        accountRetractionRepository.save(
            AccountRetraction(
                accountId = accountId,
                attributeType = type,
                normalizedValue = normalizedValue,
                trustAnchor = trustAnchor,
                reason = reason,
                retractedAt = at
            )
        )
    }
}
