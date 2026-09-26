package com.example.dpop.account.infrastructure

import com.example.dpop.tool_spi.AttributeType
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

/**
 * Round-trips [AttributeType] through its [AttributeType.wireName], not `@Enumerated(STRING)`'s
 * enum-constant-name (`PERSON_ID`) - `account.claim.attribute_type` already holds wire names
 * (`person_id`) written since before this converter existed (C2, docs/archiv/2026-review-domaenen-db-
 * modell.md), and switching to the default `@Enumerated(EnumType.STRING)` encoding would silently
 * stop matching every row already on disk.
 */
@Converter
class AttributeTypeConverter : AttributeConverter<AttributeType, String> {
    override fun convertToDatabaseColumn(attribute: AttributeType?): String? = attribute?.wireName
    override fun convertToEntityAttribute(dbData: String?): AttributeType? =
        // A stored name that is no AttributeType is corrupt data, not input to refuse politely.
        dbData?.let { checkNotNull(AttributeType.fromWireName(it)) { "Unknown attribute type in the database: $it" } }
}
