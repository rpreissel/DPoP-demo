package com.example.dpop.account.infrastructure

import com.example.dpop.account.application.IdentityMatchingService
import com.example.dpop.account.domain.normalizeClaimValue
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
 * never overwritten, only appended to. The current value of an anchor attribute is consolidated
 * into [AccountAnchor]; attribute matching (`IdentityMatchingService`) reads [normalizedValue].
 * Consolidation precedence: anchor class first, recency only as tiebreaker (ADR-11,
 * docs/12-entscheidungen.md).
 */
@Entity
@Table(schema = "account", name = "claim")
class AccountClaim(
    @Column(name = "account_id", nullable = false)
    var accountId: Long? = null,

    @Convert(converter = AttributeTypeConverter::class)
    @Column(name = "attribute_type", nullable = false)
    var attributeType: AttributeType? = null,

    @Column(name = "claim_value", nullable = false)
    var value: String? = null,

    @Column(name = "normalized_value", nullable = false)
    var normalizedValue: String? = null,

    // Raw String, not the ClaimSource value class: Hibernate hands an AttributeConverter the unboxed
    // String for a Kotlin value-class property and fails at runtime (docs/archiv/2026-review-domaenen-db-modell.md C2).
    @Column(name = "claim_source", nullable = false)
    var claimSource: String? = null,

    @Column(name = "established_acr")
    var establishedAcr: String? = null,

    /**
     * The method instance whose enrollment established this claim, so revoking that method can
     * retract exactly what it asserted (ADR-12). `null` for claims from identification tools:
     * they produce no credential and therefore no instance to hang the claim on.
     */
    @Column(name = "auth_method_id")
    var authMethodId: java.util.UUID? = null,

    @Column(name = "established_at", nullable = false)
    var establishedAt: Instant? = null
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @PrePersist
    @PreUpdate
    fun normalizeValue() {
        normalizedValue = normalizeClaimValue(attributeType, value)
    }
}
