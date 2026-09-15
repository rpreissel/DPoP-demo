package com.example.dpop.account.internal

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * One claim about an account's identity, with its own trust anchor and the LOA it was
 * established at - never overwritten, only appended to
 * (docs/ideen/claims-modell-und-vertrauensanker.md, Phase 1: log plus write-time consolidation
 * only). `account`'s own columns (e.g. `personId`) remain the actively consolidated projection,
 * set by the existing write paths; nothing reads this table back yet. Consolidation precedence
 * once something does: anchor class first, recency only as tiebreaker (ADR-11,
 * docs/12-entscheidungen.md).
 */
@Entity
@Table(name = "account_attribute")
class AccountAttribute(
    @Column(name = "account_id", nullable = false)
    var accountId: Long? = null,

    @Column(name = "attribute_type", nullable = false)
    var attributeType: String? = null,

    @Column(name = "attribute_value")
    var value: String? = null,

    @Column(name = "trust_anchor", nullable = false)
    var trustAnchor: String? = null,

    @Column(name = "established_loa")
    var establishedLoa: String? = null,

    @Column(name = "established_at", nullable = false)
    var establishedAt: Instant? = null
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
}
