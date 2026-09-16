package com.example.dpop.account.internal

import com.example.dpop.tool_api.AnchorType
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

/**
 * Round-trips [AnchorType] through its [AnchorType.wireName] - `account_anchor.anchor_type`
 * already holds wire names (`email`, `kvnr`), so this preserves the existing on-disk format
 * exactly, no data migration needed (C2, docs/13-review-domaenen-db-modell.md).
 */
@Converter
class AnchorTypeConverter : AttributeConverter<AnchorType, String> {
    override fun convertToDatabaseColumn(attribute: AnchorType?): String? = attribute?.wireName
    override fun convertToEntityAttribute(dbData: String?): AnchorType? = dbData?.let(AnchorType::fromWireName)
}
