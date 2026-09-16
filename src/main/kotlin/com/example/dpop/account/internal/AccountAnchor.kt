package com.example.dpop.account.internal

import com.example.dpop.tool_spi.AttributeType
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * One resolvable anchor value an account has established - the physical form of the claims
 * model's resolve direction (docs/ideen/claims-modell-und-vertrauensanker.md, "Dreiteilung
 * statt einer Tabelle"): `UNIQUE(anchor_type, value)` is what makes `resolveByAnchor` a
 * lookup instead of a match. Values are stored in the anchor type's normalized form; this
 * table is a projection (may be rebuilt from `account_attribute`'s provenance log, may lose
 * its row when an anchor is re-bound), so it carries no trust anchor of its own. First
 * writer wins across accounts - cross-account conflicts stay upstream rejections (ADR-11,
 * docs/12-entscheidungen.md), never silent re-assignment.
 */
@Entity
@Table(name = "account_anchor")
class AccountAnchor(
    // Reuses AttributeTypeConverter (account_attribute.attribute_type's own converter): both
    // columns round-trip through the same AttributeType.wireName, and account_anchor.anchor_type
    // already holds those wire names on disk (kvnr, email) - no second converter needed.
    @Convert(converter = AttributeTypeConverter::class)
    @Column(name = "anchor_type", nullable = false)
    var attributeType: AttributeType? = null,

    @Column(name = "anchor_value", nullable = false)
    var value: String? = null,

    @Column(name = "account_id", nullable = false)
    var accountId: Long? = null,

    @Column(name = "established_at", nullable = false)
    var establishedAt: Instant? = null
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
}
