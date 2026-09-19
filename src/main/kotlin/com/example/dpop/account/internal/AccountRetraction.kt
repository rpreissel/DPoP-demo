package com.example.dpop.account.internal

import com.example.dpop.account.RetractionAnchor
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
 * One withdrawn (account, attribute type, value) triple. Cancels every matching row in
 * [AccountClaim] without touching it: the claim log stays strictly append-only, and
 * "currently valid" is the subtraction of these rows from it (`IdentityMatchingService`).
 * Matched on [normalizedValue] for the same reason the log is - the raw spelling stays readable
 * in the claim rows themselves.
 */
@Entity
@Table(schema = "account", name = "retraction")
class AccountRetraction(
    @Column(name = "account_id", nullable = false)
    var accountId: Long? = null,

    @Convert(converter = AttributeTypeConverter::class)
    @Column(name = "attribute_type", nullable = false)
    var attributeType: AttributeType? = null,

    @Column(name = "normalized_value", nullable = false)
    var normalizedValue: String? = null,

    @Column(name = "trust_anchor", nullable = false)
    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    var trustAnchor: RetractionAnchor? = null,

    @Column(name = "reason")
    var reason: String? = null,

    @Column(name = "retracted_at", nullable = false)
    var retractedAt: Instant? = null
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
}
