package com.example.dpop.tool_api

import com.example.dpop.tool_spi.AttributeType

/**
 * The anchor role an attribute type can carry: a value accounts and identities are looked
 * up BY - the resolve direction of the claims model, as opposed to mere projection
 * attributes (docs/ideen/claims-modell-und-vertrauensanker.md, "Zwei Sorten Auflösung").
 * Sealed on purpose: a new anchor kind is a new case with its own normalization rule, not a
 * new string. This is the one place where an anchor's normalization lives - applied
 * identically on write (`AccountService` stores the normalized form) and on lookup
 * ([AccountDirectory.resolveByAnchor]), so spellings agree regardless of who typed them.
 */
sealed interface AnchorType {
    /** Storage/wire name - also the `anchor_type` column value and the [AttributeType.wireName] it maps from. */
    val wireName: String

    /** Canonical form of [value] for storage and lookup. */
    fun normalize(value: String): String

    /** KVNR - external identifier, folded to the canonical uppercase form. */
    object Kvnr : AnchorType {
        override val wireName = "kvnr"
        override fun normalize(value: String): String = value.trim().uppercase()
    }

    /** Email - the canonical confirmed address, case-insensitive, folded to lowercase. */
    object Email : AnchorType {
        override val wireName = "email"
        override fun normalize(value: String): String = value.trim().lowercase()
    }

    companion object {
        /**
         * The anchor role of a claim's attribute type, or `null` if that attribute is no
         * anchor. New attribute types become anchors only once they grow a case above
         * (`phone_number` is the documented next candidate).
         */
        fun of(attributeType: AttributeType): AnchorType? = when (attributeType) {
            AttributeType.KVNR -> Kvnr
            AttributeType.EMAIL -> Email
            AttributeType.PERSON_ID,
            AttributeType.NAME,
            AttributeType.VORNAME,
            AttributeType.GEBURTSDATUM,
            AttributeType.PHONE_NUMBER -> null
        }

        /** Reverse of [wireName] - the JPA persistence converter's only caller (C2, account.internal.AnchorTypeConverter). */
        fun fromWireName(wireName: String): AnchorType = when (wireName) {
            Kvnr.wireName -> Kvnr
            Email.wireName -> Email
            else -> error("Unknown anchor type wire name: $wireName")
        }
    }
}
