package com.example.dpop.account.internal

import com.example.dpop.tool_spi.AttributeType
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.PrePersist
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import java.time.Instant

/**
 * One claim about an account's identity, with its source and the LOA it was established at -
 * never overwritten, only appended to (docs/ideen/claims-modell-und-vertrauensanker.md). The
 * current value of an anchor attribute is consolidated into [AccountAnchor]; attribute matching
 * (`IdentityMatchingService`) reads [normalizedValue]. Consolidation precedence: anchor class
 * first, recency only as tiebreaker (ADR-11, docs/12-entscheidungen.md).
 */
@Entity
@Table(name = "account_attribute")
class AccountAttribute(
    @Column(name = "account_id", nullable = false)
    var accountId: Long? = null,

    @Convert(converter = AttributeTypeConverter::class)
    @Column(name = "attribute_type", nullable = false)
    var attributeType: AttributeType? = null,

    @Column(name = "attribute_value", nullable = false)
    var value: String? = null,

    @Column(name = "normalized_value", nullable = false)
    var normalizedValue: String? = null,

    // Raw String, not the ClaimSource value class: Hibernate hands an AttributeConverter the unboxed
    // String for a Kotlin value-class property and fails at runtime (docs/13-review-domaenen-db-modell.md C2).
    @Column(name = "claim_source", nullable = false)
    var claimSource: String? = null,

    @Column(name = "established_loa")
    var establishedLoa: String? = null,

    @Column(name = "established_at", nullable = false)
    var establishedAt: Instant? = null
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @PrePersist
    @PreUpdate
    fun normalizeValue() {
        normalizedValue = normalize(value)
    }

    companion object {
        /**
         * The one place the normalization rule exists - the write-time hook above and every
         * caller that queries by [normalizedValue] (`IdentityMatchingService`) must go through
         * this, or the index (`ix_account_attribute_type_value`) silently
         * stops matching.
         */
        fun normalize(value: String?): String? = value?.trim()?.lowercase()
    }
}
