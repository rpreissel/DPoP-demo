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
 * The one current value an account holds for a locally owned attribute
 * (`AttributeType.authority == LOCAL_ANCHOR`), in its normalized form - at most one per
 * (account, attribute type).
 * `UNIQUE(attribute_type, normalized_value)` makes resolving an identity a lookup instead of a
 * match and is the only uniqueness authority for that value. First writer wins across accounts - a
 * cross-account conflict is an upstream rejection (ADR-11, docs/12-entscheidungen.md), never a
 * re-assignment. Provenance stays in [AccountAttribute].
 */
@Entity
@Table(schema = "account", name = "anchor")
class AccountAnchor(
    @Column(name = "account_id", nullable = false)
    var accountId: Long? = null,

    // Same converter as account.attribute.attribute_type: both columns hold AttributeType.wireName.
    @Convert(converter = AttributeTypeConverter::class)
    @Column(name = "attribute_type", nullable = false)
    var attributeType: AttributeType? = null,

    @Column(name = "normalized_value", nullable = false)
    var value: String? = null,

    @Column(name = "established_at", nullable = false)
    var establishedAt: Instant? = null
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
}
